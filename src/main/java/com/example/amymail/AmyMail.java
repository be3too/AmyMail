package com.example.amymail;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

public class AmyMail implements ModInitializer {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("dd.MM HH:mm");

    public static class MailEntry {
        public String recipientUuid;
        public String recipientName;
        public String senderName;
        public String timestamp;
        public String content;
        public boolean delivered;

        public MailEntry(String recipientUuid, String recipientName, String senderName, String timestamp, String content, boolean delivered) {
            this.recipientUuid = recipientUuid;
            this.recipientName = recipientName;
            this.senderName = senderName;
            this.timestamp = timestamp;
            this.content = content;
            this.delivered = delivered;
        }
    }
    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            // 1. Command /ams <player> <message>
            dispatcher.register(Commands.literal("ams")
                .then(Commands.argument("target", StringArgumentType.word())
                    .suggests((context, builder) -> {
                        MinecraftServer server = context.getSource().getServer();
                        return SharedSuggestionProvider.suggest(server.getPlayerNames(), builder);
                    })
                    .then(Commands.argument("message", StringArgumentType.greedyString())
                        .executes(context -> {
                            ServerPlayer sender = context.getSource().getPlayerOrException();
                            MinecraftServer server = context.getSource().getServer();
                            String targetName = StringArgumentType.getString(context, "target");
                            String msg = StringArgumentType.getString(context, "message");

                            if (targetName.equalsIgnoreCase(sender.getName().getString())) {
                                sender.sendSystemMessage(
                                    Component.literal("§5✦ §dAmyMail §8» §7You cannot send mail to yourself.")
                                );
                                return 0;
                            }

                            ServerPlayer onlineTarget = server.getPlayerList().getPlayerByName(targetName);
                            String now = LocalDateTime.now().format(TIME_FORMAT);

                            if (onlineTarget != null) {
                                if (isIgnoredBy(onlineTarget.getUUID().toString(), sender.getName().getString())) {
                                    sender.sendSystemMessage(
                                        Component.literal("§5✦ §dAmyMail §8» §7This player is ignoring your messages.")
                                    );
                                    return 0;
                                }

                                onlineTarget.playNotifySound(SoundEvents.AMETHYST_BLOCK_BREAK, SoundSource.MASTER, 1.0F, 1.0F);
                                onlineTarget.sendSystemMessage(
                                    Component.literal("§5✦ §dAmyMail §8» §6" + sender.getName().getString() + " -> You: §6" + msg)
                                );

                                sender.sendSystemMessage(
                                    Component.literal("§5✦ §dAmyMail §8» §6You -> " + onlineTarget.getName().getString() + ": §e" + msg)
                                );

                                List<MailEntry> allMail = loadAllMail();
                                allMail.add(new MailEntry(
                                    onlineTarget.getUUID().toString(),
                                    onlineTarget.getName().getString(),
                                    sender.getName().getString(),
                                    now,
                                    msg,
                                    true
                                ));
                                saveAllMail(allMail);
                            } else {
                                List<MailEntry> allMail = loadAllMail();
                                allMail.add(new MailEntry(
                                    null,
                                    targetName,
                                    sender.getName().getString(),
                                    now,
                                    msg,
                                    false
                                ));
                                saveAllMail(allMail);

                                sender.sendSystemMessage(
                                    Component.literal("§5✦ §dAmyMail §8» §6Queued for " + targetName + " (offline): §e" + msg)
                                );
                            }
                            return 1;
                        })
                    )
                )
            );
            // 2. Command /mailbox and /mailbox clear
            dispatcher.register(Commands.literal("mailbox")
                .executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    String playerUuid = player.getUUID().toString();
                    List<MailEntry> allMail = loadAllMail();

                    List<MailEntry> myMail = new ArrayList<>();
                    for (MailEntry entry : allMail) {
                        if (entry.recipientUuid != null && entry.recipientUuid.equals(playerUuid)) {
                            myMail.add(entry);
                        }
                    }

                    if (myMail.isEmpty()) {
                        player.sendSystemMessage(
                            Component.literal("§5✦ §dAmyMail §8» §7Your private mailbox is empty.")
                        );
                        return 1;
                    }

                    player.sendSystemMessage(
                        Component.literal("§5╭─── §d✦ AmyMail §8• §7Inbox for §d" + player.getName().getString() + " §5───")
                    );

                    for (MailEntry mail : myMail) {
                        player.sendSystemMessage(
                            Component.literal("§5│ §8[" + mail.timestamp + "] §6" + mail.senderName + "§8: §6" + mail.content)
                        );
                    }

                    player.sendSystemMessage(
                        Component.literal("§5╰────────────────────────────────────")
                    );
                    return 1;
                })
                .then(Commands.literal("clear")
                    .executes(context -> {
                        ServerPlayer player = context.getSource().getPlayerOrException();
                        String playerUuid = player.getUUID().toString();
                        List<MailEntry> allMail = loadAllMail();

                        allMail.removeIf(entry -> entry.recipientUuid != null && entry.recipientUuid.equals(playerUuid));
                        saveAllMail(allMail);

                        player.sendSystemMessage(
                            Component.literal("§5✦ §dAmyMail §8» §7Your mailbox has been cleared.")
                        );
                        return 1;
                    })
                )
            );
            // 3. Command /amignore <player> and /amignore list
            dispatcher.register(Commands.literal("amignore")
                .then(Commands.literal("list")
                    .executes(context -> {
                        ServerPlayer player = context.getSource().getPlayerOrException();
                        List<String> ignored = loadIgnored(player.getUUID().toString());

                        if (ignored.isEmpty()) {
                            player.sendSystemMessage(
                                Component.literal("§5✦ §dAmyMail §8» §7Your ignore list is empty.")
                            );
                        } else {
                            player.sendSystemMessage(
                                Component.literal("§5✦ §dAmyMail §8» §7Ignored players: §d" + String.join("§7, §d", ignored))
                            );
                        }
                        return 1;
                    })
                )
                .then(Commands.argument("player", StringArgumentType.word())
                    .suggests((context, builder) -> {
                        MinecraftServer server = context.getSource().getServer();
                        return SharedSuggestionProvider.suggest(server.getPlayerNames(), builder);
                    })
                    .executes(context -> {
                        ServerPlayer player = context.getSource().getPlayerOrException();
                        String target = StringArgumentType.getString(context, "player");
                        String uuid = player.getUUID().toString();

                        List<String> ignored = loadIgnored(uuid);
                        boolean removed = ignored.removeIf(name -> name.equalsIgnoreCase(target));

                        if (removed) {
                            saveIgnored(uuid, ignored);
                            player.sendSystemMessage(
                                Component.literal("§5✦ §dAmyMail §8» §7Removed §d" + target + " §7from your ignore list.")
                            );
                        } else {
                            ignored.add(target);
                            saveIgnored(uuid, ignored);
                            player.sendSystemMessage(
                                Component.literal("§5✦ §dAmyMail §8» §7Added §d" + target + " §7to your ignore list.")
                            );
                        }
                        return 1;
                    })
                )
            );
        });
        // 4. Deliver offline messages automatically when a player joins the server
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.getPlayer();
            String playerName = player.getName().getString();
            String playerUuid = player.getUUID().toString();

            List<MailEntry> allMail = loadAllMail();
            List<MailEntry> pending = new ArrayList<>();
            boolean changed = false;

            Iterator<MailEntry> iterator = allMail.iterator();
            while (iterator.hasNext()) {
                MailEntry entry = iterator.next();
                boolean matchesPlayer = (entry.recipientUuid != null && entry.recipientUuid.equals(playerUuid))
                    || (entry.recipientName != null && entry.recipientName.equalsIgnoreCase(playerName));

                if (!entry.delivered && matchesPlayer) {
                    if (isIgnoredBy(playerUuid, entry.senderName)) {
                        iterator.remove();
                        changed = true;
                    } else {
                        entry.recipientUuid = playerUuid;
                        entry.recipientName = playerName;
                        entry.delivered = true;
                        pending.add(entry);
                        changed = true;
                    }
                }
            }

            if (changed) {
                saveAllMail(allMail);
            }

            if (!pending.isEmpty()) {
                player.playNotifySound(SoundEvents.AMETHYST_BLOCK_BREAK, SoundSource.MASTER, 1.0F, 1.2F);
                player.sendSystemMessage(
                    Component.literal("§5✦ §dAmyMail §8» §7You received §d" + pending.size() + " §7new message(s) while offline:")
                );

                for (MailEntry mail : pending) {
                    player.sendSystemMessage(
                        Component.literal("§5│ §8[" + mail.timestamp + "] §6" + mail.senderName + "§8: §6" + mail.content)
                    );
                }
            }
        });
    }

    private Path getConfigDir() {
        Path dir = FabricLoader.getInstance().getConfigDir().resolve("amymail");
        try {
            Files.createDirectories(dir);
        } catch (Exception ignored) {}
        return dir;
    }

    private List<MailEntry> loadAllMail() {
        Path file = getConfigDir().resolve("server_mail.json");
        if (!Files.exists(file)) return new ArrayList<>();
        try (Reader reader = Files.newBufferedReader(file)) {
            Type listType = new TypeToken<ArrayList<MailEntry>>() {}.getType();
            List<MailEntry> list = GSON.fromJson(reader, listType);
            return list != null ? list : new ArrayList<>();
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private void saveAllMail(List<MailEntry> list) {
        Path file = getConfigDir().resolve("server_mail.json");
        try (Writer writer = Files.newBufferedWriter(file)) {
            GSON.toJson(list, writer);
        } catch (Exception ignored) {}
    }

    private boolean isIgnoredBy(String recipientUuid, String senderName) {
        List<String> ignored = loadIgnored(recipientUuid);
        for (String name : ignored) {
            if (name.equalsIgnoreCase(senderName)) {
                return true;
            }
        }
        return false;
    }

    private List<String> loadIgnored(String playerUuid) {
        Path file = getConfigDir().resolve("ignore_" + playerUuid + ".json");
        if (!Files.exists(file)) return new ArrayList<>();
        try (Reader reader = Files.newBufferedReader(file)) {
            Type listType = new TypeToken<ArrayList<String>>() {}.getType();
            List<String> list = GSON.fromJson(reader, listType);
            return list != null ? list : new ArrayList<>();
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private void saveIgnored(String playerUuid, List<String> ignored) {
        Path file = getConfigDir().resolve("ignore_" + playerUuid + ".json");
        try (Writer writer = Files.newBufferedWriter(file)) {
            GSON.toJson(ignored, writer);
        } catch (Exception ignoredEx) {}
    }
}
