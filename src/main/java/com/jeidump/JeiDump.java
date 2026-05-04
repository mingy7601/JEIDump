package com.jeidump;

import net.minecraft.client.Minecraft;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import org.apache.logging.log4j.Logger;

import com.jeidump.command.CommandDumpJei;

/**
 * Entry point for JEI Dump.
 *
 * Provides a client-side {@code /dumpjei} command that exports every JEI recipe (vanilla + plugins)
 * along with its rendered icons and full layout image into a self-contained static HTML+JS site
 * placed under {@code <gameDir>/jeidump/}.
 *
 * The mod itself only adds a command and a JEI plugin to capture {@code IJeiRuntime}; all heavy
 * lifting happens lazily when the command runs.
 */
@Mod(modid = JeiDump.MOD_ID,
    name = JeiDump.MOD_NAME,
    version = JeiDump.VERSION,
    acceptedMinecraftVersions = "[1.12.2,1.13)",
    dependencies = "required-after:jei@[4.16.0,);",
    clientSideOnly = true)
public class JeiDump {

    public static final String MOD_ID = "jeidump";
    public static final String MOD_NAME = "JEI Dump";
    public static final String VERSION = "1.0.0";

    public static Logger LOGGER;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        LOGGER = event.getModLog();
    }

    @Mod.EventHandler
    @SideOnly(Side.CLIENT)
    public void init(FMLInitializationEvent event) {
        // Client commands are registered with Forge's ClientCommandHandler; they work on /dumpjei in singleplayer
        // and on multiplayer servers without needing op or any server-side counterpart.
        if (Minecraft.getMinecraft() != null) {
            ClientCommandHandler.instance.registerCommand(new CommandDumpJei());
        }
    }
}
