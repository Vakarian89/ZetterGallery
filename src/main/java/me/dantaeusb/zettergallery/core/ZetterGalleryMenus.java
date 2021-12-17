package me.dantaeusb.zettergallery.core;

import me.dantaeusb.zettergallery.ZetterGallery;
import me.dantaeusb.zettergallery.menu.PaintingMerchantMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = ZetterGallery.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class ZetterGalleryMenus {
    public static MenuType<PaintingMerchantMenu> PAINTING_MERCHANT;

    @SubscribeEvent
    @SuppressWarnings("unused")
    public static void onContainerRegistry(final RegistryEvent.Register<MenuType<?>> event)
    {
        PAINTING_MERCHANT = IForgeMenuType.create(PaintingMerchantMenu::createMenuClientSide);
        PAINTING_MERCHANT.setRegistryName(ZetterGallery.MOD_ID, "painting_merchant_container");
        event.getRegistry().register(PAINTING_MERCHANT);
    }
}
