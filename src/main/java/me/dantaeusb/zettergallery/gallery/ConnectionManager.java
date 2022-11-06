package me.dantaeusb.zettergallery.gallery;

import me.dantaeusb.zetter.Zetter;
import me.dantaeusb.zetter.storage.PaintingData;
import me.dantaeusb.zettergallery.ZetterGallery;
import me.dantaeusb.zettergallery.container.PaintingMerchantContainer;
import me.dantaeusb.zettergallery.core.Helper;
import me.dantaeusb.zettergallery.gallery.salesmanager.PlayerFeed;
import me.dantaeusb.zettergallery.network.http.GalleryConnection;
import me.dantaeusb.zettergallery.network.http.GalleryError;
import me.dantaeusb.zettergallery.network.http.stub.PaintingsResponse;
import me.dantaeusb.zettergallery.trading.PaintingMerchantOffer;
import me.dantaeusb.zettergallery.util.EventConsumer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * This singleton is responsible for Gallery interaction
 * on behalf of a server.
 * <p>
 * Deferred server registration
 */
public class ConnectionManager {
    private static @Nullable ConnectionManager instance;

    private final GalleryConnection connection;
    private final PlayerTokenStorage playerTokenStorage = PlayerTokenStorage.getInstance();

    private Level overworld;
    private ServerInfo serverInfo;

    private Token serverToken;
    private Token refreshToken;
    private UUID serverUuid;

    private ConnectionStatus status = ConnectionStatus.WAITING;
    private String errorMessage = "Connection is not ready";

    private long errorTimestamp;

    /**
     * Player feed cache
     */
    private final HashMap<UUID, PlayerFeed> playerFeeds = new HashMap<>();

    private long nextCycleEpoch;
    private String cycleSeed;

    private ConnectionManager(Level overworld) {
        this.overworld = overworld;
        this.connection = new GalleryConnection();

        instance = this;
    }

    public static ConnectionManager getInstance() {
        if (ConnectionManager.instance == null) {
            throw new IllegalStateException("Connection Manager is not ready, no client app capability in the world");
        }

        return instance;
    }

    public static void initialize(Level overworld) {
        ConnectionManager.instance = new ConnectionManager(overworld);
    }

    public static void close() {
        ConnectionManager.instance = null;
    }

    /**
     * Check that current feed is not outdated, and update if it is
     * @todo: Update if player has trading screen opened
     */
    public void update() {
        if (this.nextCycleEpoch == 0L) {
            return;
        }

        long unixTime = System.currentTimeMillis();

        if (this.playerFeeds.size() > 0 && unixTime > this.nextCycleEpoch) {
            this.playerFeeds.clear();
        }
    }

    public void handleServerStart(MinecraftServer server) {
        if (server.isDedicatedServer()) {
            this.serverInfo = ServerInfo.createMultiplayerServer(server.getLocalIp() + ":" + server.getPort(), server.getMotd(), server.getServerVersion());
        } else {
            this.serverInfo = ServerInfo.createSingleplayerServer(server.getServerVersion());
        }
    }

    public void handleServerStop(MinecraftServer server) {
        if (this.status == ConnectionStatus.READY) {
            this.dropServerToken();
        }
    }

    public GalleryConnection getConnection() {
        return this.connection;
    }

    private boolean hasToken() {
        return this.serverToken == null || !this.serverToken.valid();
    }

    enum ConnectionStatus {
        WAITING,
        READY,
        ERROR,
        UNRECOVERABLE_ERROR,
    }

    /*
     * Player
     */

    /**
     * Request auth code for particular player for this server
     * @param player
     * @param tokenConsumer
     * @param errorConsumer
     */
    private void registerServerPlayer(ServerPlayer player, Consumer<PlayerToken> tokenConsumer, Consumer<GalleryError> errorConsumer) {
        GalleryServerCapability galleryServerCapability = (GalleryServerCapability) Helper.getWorldGalleryCapability(this.overworld);

        this.connection.registerPlayer(
                this.serverToken.token,
                player,
                (response) -> {
                    PlayerToken playerReservedToken = new PlayerToken(
                            response.token.token,
                            response.token.issuedAt,
                            response.token.notAfter
                    );

                    if (response.poolingAuthorizationCode != null) {
                        playerReservedToken.setAuthorizationCode(response.poolingAuthorizationCode);
                    }

                    // @todo: [HIGH] Save authorization code
                    this.playerTokenStorage.setPlayerToken(player, playerReservedToken);

                    tokenConsumer.accept(playerReservedToken);
                },
                errorConsumer
        );
    }

    /**
     * Start server player authorization flow. If we have a token
     * already, just check if it works and what rights we have.
     * If we don't, request token and cross-authorization code,
     * which later will be sent to client. When client returns
     * to game after authorizing server, another call for this method
     * will be received and we'll be checking token access.
     *
     * @param player
     */
    public void authenticateServerPlayer(ServerPlayer player, Consumer<PlayerToken> successConsumer, Consumer<GalleryError> errorConsumer) {
        // Check that server is registered, try again when registered or handle error if cannot be registered
        if (!this.authenticateServerClient(
                (token) -> {
                    this.authenticateServerPlayer(player, successConsumer, errorConsumer);
                },
                errorConsumer
        )) {
            return;
        }

        if (this.playerTokenStorage.hasPlayerToken(player)) {
            PlayerToken playerToken = this.playerTokenStorage.getPlayerToken(player);
            assert playerToken != null;

            // @todo: [MID] Check if playerToken expired
            if (!playerToken.valid()) {
                this.playerTokenStorage.removePlayerToken(player);

                this.registerServerPlayer(
                        player,
                        successConsumer,
                        errorConsumer
                );

                return;
            }

            if (playerToken.isAuthorized()) {
                successConsumer.accept(playerToken);
            } else if (playerToken.authorizationCode != null) {
                GalleryServerCapability galleryServerCapability = (GalleryServerCapability) Helper.getWorldGalleryCapability(this.overworld);

                this.connection.requestServerPlayerToken(
                        galleryServerCapability.getClientInfo(),
                        playerToken.token,
                        playerToken.authorizationCode.code,
                        authTokenResponse -> {
                            this.playerTokenStorage.removePlayerToken(player);

                            PlayerToken newPlayerToken = new PlayerToken(
                                    authTokenResponse.token,
                                    authTokenResponse.issuedAt,
                                    authTokenResponse.notAfter
                            );

                            // @todo: [HIGH] this is wrong, ask Gallery
                            newPlayerToken.setAuthorizedAs(new PlayerToken.PlayerInfo(
                                    player.getUUID(),
                                    player.getName().getString()
                            ));

                            this.playerTokenStorage.setPlayerToken(player, newPlayerToken);

                            successConsumer.accept(newPlayerToken);
                        },
                        error -> {
                            // @todo: [MED] When something went wrong, how do we reset the code?
                            ZetterGallery.LOG.error(error.getMessage());

                            successConsumer.accept(playerToken);
                        }
                );
            } else {
                errorConsumer.accept(new GalleryError(GalleryError.UNKNOWN, "No authorization code"));
            }
        } else {
            this.registerServerPlayer(
                    player,
                    successConsumer,
                    errorConsumer
            );
        }
    }

    /*
     * Paintings
     */

    public void registerImpression(ServerPlayer player, UUID paintingUuid, EventConsumer successConsumer, EventConsumer errorConsumer) {
        ConnectionManager.getInstance().getConnection().registerImpression(
                this.playerTokenStorage.getPlayerTokenString(player),
                paintingUuid,
                (response) -> {
                    successConsumer.accept();
                },
                (exception) -> {
                    // throw away error data, we cannot do anything about unregestired impression
                    errorConsumer.accept();
                }
        );
    }

    public void registerPurchase(ServerPlayer player, UUID paintingUuid, int price, EventConsumer successConsumer, Consumer<GalleryError> errorConsumer) {
        ConnectionManager.getInstance().getConnection().registerPurchase(
                this.playerTokenStorage.getPlayerTokenString(player),
                paintingUuid,
                price,
                (response) -> {
                    successConsumer.accept();
                },
                errorConsumer
        );
    }

    public void validateSale(ServerPlayer player, PaintingMerchantOffer offer, EventConsumer successConsumer, Consumer<GalleryError> errorConsumer) {
        PlayerFeed playerFeed = this.playerFeeds.get(player.getUUID());

        if (playerFeed == null) {
            errorConsumer.accept(new GalleryError(GalleryError.PLAYER_FEED_UNAVAILABLE, "Unable to load feed"));
            return;
        }

        if (!playerFeed.isSaleAllowed()) {
            errorConsumer.accept(new GalleryError(GalleryError.SERVER_SALE_DISALLOWED, "Sale is not allowed on this server"));
            return;
        }

        if (offer.getPaintingData().isEmpty()) {
            errorConsumer.accept(new GalleryError(GalleryError.SERVER_RECEIVED_INVALID_PAINTING_DATA, "Painting data not ready"));
            return;
        }

        ConnectionManager.getInstance().getConnection().validatePainting(
                this.playerTokenStorage.getPlayerTokenString(player),
                offer.getPaintingData().get(),
                (response) -> successConsumer.accept(),
                errorConsumer
        );
    }

    public void registerSale(ServerPlayer player, PaintingData paintingData, EventConsumer successConsumer, Consumer<GalleryError> errorConsumer) {
        ConnectionManager.getInstance().getConnection().sellPainting(
                this.playerTokenStorage.getPlayerTokenString(player),
                paintingData,
                (response) -> {
                    successConsumer.accept();
                },
                errorConsumer
        );
    }

    /*
     * Feed
     */

    public void requestOffers(ServerPlayer player, PaintingMerchantContainer paintingMerchantContainer, Consumer<List<PaintingMerchantOffer>> successConsumer, Consumer<GalleryError> errorConsumer) {
        if (this.playerFeeds.containsKey(player.getUUID())) {
            PlayerFeed feed = this.playerFeeds.get(player.getUUID());
            List<PaintingMerchantOffer> offers = this.getOffersFromFeed(
                    this.cycleSeed,
                    feed,
                    paintingMerchantContainer.getMenu().getMerchantId(),
                    paintingMerchantContainer.getMenu().getMerchantLevel()
            );

            successConsumer.accept(offers);
        } else {
            // Will call handlePlayerFeed on response, which call handleOffers
            this.getConnection().getPlayerFeed(
                    this.playerTokenStorage.getPlayerTokenString(player),
                    (response) -> {
                        this.cycleSeed = response.cycleInfo.seed;
                        this.nextCycleEpoch = response.cycleInfo.endsAt.getTime();

                        PlayerFeed feed = this.createPlayerFeed(player, response);
                        List<PaintingMerchantOffer> offers = this.getOffersFromFeed(
                                this.cycleSeed,
                                feed,
                                paintingMerchantContainer.getMenu().getMerchantId(),
                                paintingMerchantContainer.getMenu().getMerchantLevel()
                        );

                        successConsumer.accept(offers);
                    },
                    errorConsumer
            );
        }
    }

    private PlayerFeed createPlayerFeed(ServerPlayer player, PaintingsResponse response) {
        PlayerFeed feed = PlayerFeed.createFeedFromSaleResponse(player, response);

        this.playerFeeds.put(player.getUUID(), feed);

        return feed;
    }

    /**
     * Depending on merchant ID and level pick some paintings from feed to show on sale
     *
     * @param feed
     * @param merchantId
     * @param merchantLevel
     * @return
     */
    private List<PaintingMerchantOffer> getOffersFromFeed(String seed, PlayerFeed feed, UUID merchantId, int merchantLevel) {
        ByteBuffer buffer = ByteBuffer.wrap(seed.getBytes(StandardCharsets.UTF_8), 0, 8);
        long seedLong = buffer.getLong();

        Random rng = new Random(seedLong ^ feed.getPlayer().getUUID().getMostSignificantBits() ^ merchantId.getMostSignificantBits());

        final int totalCount = feed.getOffersCount();

        int showCount = 5 + merchantLevel * 2;
        showCount = Math.min(showCount, totalCount);

        List<Integer> available = IntStream.range(0, totalCount).boxed().collect(Collectors.toList());
        Collections.shuffle(available, rng);
        available = available.subList(0, showCount);

        List<PaintingMerchantOffer> randomOffers = available.stream().map(feed.getOffers()::get).collect(Collectors.toList());

        // Remove duplicates from offers list if there are same paintings in different feeds
        List<String> offerIds = new LinkedList<>();
        List<PaintingMerchantOffer> offers = new LinkedList<>();

        for (PaintingMerchantOffer offer : randomOffers) {
            if (offerIds.contains(offer.getCanvasCode())) {
                continue;
            }

            offerIds.add(offer.getCanvasCode());
            offers.add(offer);
        }

         return offers;
    }

    /*
     * Server
     */

    /**
     * Check that sever is registered and have a valid token,
     * return true if registered and ready and call success consumer,
     * return false is not and call retry or error consumer dependent
     * on the recoverability of issue.
     *
     * @param retryConsumer
     * @param errorConsumer
     * @return
     */
    private boolean authenticateServerClient(Consumer<Token> retryConsumer, @Nullable Consumer<GalleryError> errorConsumer) {
        if (this.status == ConnectionStatus.READY && this.serverToken.valid()) {
            return true;
        }

        // @todo: [HIGH] Refresh server tokens

        if (this.status == ConnectionStatus.UNRECOVERABLE_ERROR) {
            if (errorConsumer != null) {
                errorConsumer.accept(new GalleryError(GalleryError.SERVER_INVALID_VERSION, this.errorMessage));
            }

            return false;
        }

        // 30 seconds since error not yet passed, otherwise proceed
        if (this.status == ConnectionStatus.ERROR && System.currentTimeMillis() <= this.errorTimestamp + 30 * 1000) {
            if (errorConsumer != null) {
                errorConsumer.accept(new GalleryError(GalleryError.SERVER_INVALID_VERSION, this.errorMessage));
            }

            return false;
        }

        GalleryServerCapability galleryServerCapability = (GalleryServerCapability) Helper.getWorldGalleryCapability(this.overworld);
        if (galleryServerCapability.getClientInfo() != null) {
            this.getServerToken(
                    galleryServerCapability.getClientInfo(),
                    retryConsumer,
                    errorConsumer
            );
        } else {
            this.createServerClient(
                    () -> {
                        this.getServerToken(
                                galleryServerCapability.getClientInfo(),
                                retryConsumer,
                                errorConsumer
                        );
                    },
                    errorConsumer
            );
        }

        return false;
    }

    /**
     * Creates new client with extra properties and saves client id
     * and client secret to capability
     */
    private void createServerClient(EventConsumer successConsumer, @Nullable Consumer<GalleryError> errorConsumer) {
        GalleryServerCapability galleryServerCapability = (GalleryServerCapability) Helper.getWorldGalleryCapability(this.overworld);

        this.connection.createServerClient(
                this.serverInfo,
                serverResponse -> {
                    if (serverResponse.client == null) {
                        if (errorConsumer != null) {
                            errorConsumer.accept(new GalleryError(GalleryError.UNKNOWN, "Cannot find necessary client info in response"));
                        }

                        return;
                    }

                    galleryServerCapability.saveClientInfo(serverResponse.client);

                    successConsumer.accept();
                },
                error -> {
                    // Invalid version is unrecoverable
                    if (error.getCode() == 403) {
                        this.errorMessage = "Server's Zetter Gallery version is out of date. Please update.";
                        this.status = ConnectionStatus.UNRECOVERABLE_ERROR;
                    } else {
                        this.errorMessage = "Cannot connect. Please try later.";
                        this.status = ConnectionStatus.ERROR;
                    }

                    this.errorTimestamp = System.currentTimeMillis();
                    error.setClientMessage(this.errorMessage);

                    if (errorConsumer != null) {
                        errorConsumer.accept(error);
                    }
                }
        );
    }

    /**
     * Calls for new token given in exchange for client id and client secret
     * @param clientInfo
     * @param retryConsumer
     * @param errorConsumer
     */
    private void getServerToken(GalleryServerCapability.ClientInfo clientInfo, Consumer<Token> retryConsumer, @Nullable Consumer<GalleryError> errorConsumer) {
        this.connection.requestToken(
                clientInfo,
                authTokenResponse -> {
                    this.serverToken = new Token(
                            authTokenResponse.token,
                            authTokenResponse.issuedAt,
                            authTokenResponse.notAfter
                    );

                    if (authTokenResponse.refreshToken != null) {
                        this.refreshToken = new Token(
                                authTokenResponse.refreshToken.token,
                                authTokenResponse.refreshToken.issuedAt,
                                authTokenResponse.refreshToken.notAfter
                        );
                    }

                    this.status = ConnectionStatus.READY;

                    retryConsumer.accept(this.serverToken);
                },
                error -> {
                    // Cannot use client_credentials
                    if (error.getCode() == 403) {
                        GalleryServerCapability galleryServerCapability = (GalleryServerCapability) Helper.getWorldGalleryCapability(this.overworld);
                        galleryServerCapability.removeClientInfo();
                        ZetterGallery.LOG.error("Unable to use existing client credentials, got error: " + error.getMessage());
                    }

                    this.errorMessage = "Cannot connect. Please try later.";
                    this.status = ConnectionStatus.ERROR;
                    this.errorTimestamp = System.currentTimeMillis();

                    error.setClientMessage(this.errorMessage);

                    if (errorConsumer != null) {
                        errorConsumer.accept(error);
                    }
                }
        );
    }

    /**
     * Discard current token
     */
    private void dropServerToken() {
        this.playerTokenStorage.flush();

        this.connection.unregisterServer(
                this.serverToken.token,
                (message) -> {},
                Zetter.LOG::error
        );
    }
}
