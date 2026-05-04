package com.jeidump.jei;

import javax.annotation.Nullable;

import mezz.jei.api.IJeiRuntime;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.IModRegistry;
import mezz.jei.api.JEIPlugin;


/**
 * Captures the {@link IJeiRuntime} given to us once JEI has finished registering everything.
 *
 * The runtime exposes {@code getRecipeRegistry()} and {@code getIngredientRegistry()} which is
 * everything we need to enumerate categories, recipes and ingredients.
 */
@JEIPlugin
public class JeiDumpPlugin implements IModPlugin {

    @Nullable
    private static IJeiRuntime runtime;

    /**
     * Returns the captured JEI runtime, or {@code null} if JEI has not yet finished its post-init.
     * The dumper command checks this and bails out early with a friendly message if it is null.
     */
    @Nullable
    public static IJeiRuntime getRuntime() {
        return runtime;
    }

    @Override
    public void register(IModRegistry registry) {
        // Nothing to register; we're a passive consumer of the runtime.
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime jeiRuntime) {
        runtime = jeiRuntime;
    }
}
