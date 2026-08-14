package io.github.alab.mcpiano;

import com.mojang.brigadier.Command;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permissions;

/** Server-side entry point: only static blocks are placed in the world. */
public final class McPiano implements ModInitializer {
    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
                Commands.literal("piano")
                        .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                        .then(Commands.literal("build").executes(context -> {
                            var player = context.getSource().getPlayerOrException();
                            BlockPos origin = player.blockPosition();
                            PianoBuilder.build(player.level(), origin);
                            PianoOrigin.save(origin);
                            context.getSource().sendSuccess(() -> Component.literal("MC Piano built. Run /pianoviz play "
                                    + origin.getX() + " " + origin.getY() + " " + origin.getZ()), false);
                            return Command.SINGLE_SUCCESS;
                        }))
        ));
    }
}
