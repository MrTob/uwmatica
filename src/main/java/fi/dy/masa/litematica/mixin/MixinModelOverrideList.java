package fi.dy.masa.litematica.mixin;

//@Mixin(ModelOverrideList.class)
public abstract class MixinModelOverrideList
{
    /*
    @Redirect(method = "getModel", at = @At(value = "INVOKE",
              target = "Lnet/minecraft/client/item/ModelPredicateProvider;call(" +
                       "Lnet/minecraft/item/ItemStack;Lnet/minecraft/client/world/ClientWorld;" +
                       "Lnet/minecraft/entity/LivingEntity;I)F"))
    private float fixCrashWithNullWorld(ModelPredicateProvider provider,
                                        ItemStack stack,
                                        @Nullable ClientWorld world,
                                        @Nullable LivingEntity entity,
                                        int i)
    {
        if (world == null)
        {
            return provider.call(stack, MinecraftClient.getInstance().world, entity, i);
        }

        return provider.call(stack, world, entity, i);
    }
     */
}
