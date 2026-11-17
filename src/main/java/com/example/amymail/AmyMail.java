package com.example.amymail;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class AmyMail implements ClientModInitializer {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("dd.MM HH:mm");
    private static final Set<String> PREVIOUS_ONLINE = new HashSet<>();

    public static class MailEntry {
        public String ownerUuid;
        public String timestamp;
        public String content;

        public MailEntry(String ownerUuid, String timestamp, String content) {
            this.ownerUuid = ownerUuid;
            this.timestamp = timestamp;
            this.content = content;
        }
    }

    public static class OutboxEntry {
        public String recipient;
        public String content;

        public OutboxEntry(String recipient, String content) {
            this.recipient = recipient;
            this.content = content;
        }
    }
    @Override
    public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            // 1. Command /ams <player> <message>
            dispatcher.register(ClientCommandManager.literal("ams")
                .then(ClientCommandManager.argument("target", StringArgumentType.word())
                    .suggests((context, builder) -> {
                        Minecraft client = Minecraft.getInstance();
                        if (client.getConnection() != null) {
                            List<String> names = new ArrayList<>();
                            for (PlayerInfo info : client.getConnection().getOnlinePlayers()) {
                                names.add(info.getProfile().getName());
                            }
                            return SharedSuggestionProvider.suggest(names, builder);
                        }
                        return builder.buildFuture();
                    })
                    .then(ClientCommandManager.argument("message", StringArgumentType.greedyString())
                        .executes(context -> {
                            String target = StringArgumentType.getString(context, "target");
                            String msg = StringArgumentType.getString(context, "message");
                            Minecraft client = Minecraft.getInstance();

                            if (isPlayerOnline(client, target)) {
                                sendWhisper(client, target, msg);
                                if (client.player != null) {
                                    client.player.displayClientMessage(
                                        Component.literal("§5✦ §dAmyMail §8» §6You -> " + target + ": §e" + msg),
                                        false
                                    );
                                }
                            } else {
                                List<OutboxEntry> queue = loadOutbox(client);
                                queue.add(new OutboxEntry(target, msg));
                                saveOutbox(client, queue);

                                if (client.player != null) {
                                    client.player.displayClientMessage(
                                        Component.literal("§5✦ §dAmyMail §8» §6Queued for " + target + " (offline): §e" + msg),
                                        false
                                    );
                                }
                            }
                            return 1;
                        })
                    )
                )
            );
            // 2. Command /mailbox and /mailbox clear
            dispatcher.register(ClientCommandManager.literal("mailbox")
                .executes(context -> {
                    Minecraft client = Minecraft.getInstance();
                    if (client.player == null) return 0;

                    UUID currentUuid = client.player.getUUID();
                    List<MailEntry> inbox = loadInbox(client);

                    if (inbox.isEmpty()) {
                        client.player.displayClientMessage(
                            Component.literal("§5✦ §dAmyMail §8» §7Your private mailbox is empty."),
                            false
                        );
                        return 1;
                    }

                    client.player.displayClientMessage(
                        Component.literal("§5╭─── §d✦ AmyMail §8• §7Inbox for §d" + client.player.getName().getString() + " §5───"),
                        false
                    );

                    int count = 0;
                    for (MailEntry mail : inbox) {
                        if (mail.ownerUuid != null && mail.ownerUuid.equals(currentUuid.toString())) {
                            count++;
                            client.player.displayClientMessage(
                                Component.literal("§5│ §8[" + mail.timestamp + "] §6" + mail.content),
                                false
                            );
                        }
                    }

                    if (count == 0) {
                        client.player.displayClientMessage(
                            Component.literal("§5│ §7No messages found for your account."),
                            false
                        );
                    }

                    client.player.displayClientMessage(
                        Component.literal("§5╰────────────────────────────────────"),
                        false
                    );
                    return 1;
                })
                .then(ClientCommandManager.literal("clear")
                    .executes(context -> {
                        Minecraft client = Minecraft.getInstance();
                        if (client.player == null) return 0;

                        saveInbox(client, new ArrayList<>());
                        client.player.displayClientMessage(
                            Component.literal("§5✦ §dAmyMail §8» §7Your mailbox has been cleared."),
                            false
                        );
                        return 1;
                    })
                )
            );
            // 3. Command /amignore <player> and /amignore list
            dispatcher.register(ClientCommandManager.literal("amignore")
                .then(ClientCommandManager.literal("list")
                    .executes(context -> {
                        Minecraft client = Minecraft.getInstance();
                        if (client.player == null) return 0;

                        List<String> ignored = loadIgnored(client);
                        if (ignored.isEmpty()) {
                            client.player.displayClientMessage(
                                Component.literal("§5✦ §dAmyMail §8» §7Your ignore list is empty."),
                                false
                            );
                        } else {
                            client.player.displayClientMessage(
                                Component.literal("§5✦ §dAmyMail §8» §7Ignored players: §d" + String.join("§7, §d", ignored)),
                                false
                            );
                        }
                        return 1;
                    })
                )
                .then(ClientCommandManager.argument("player", StringArgumentType.word())
                    .suggests((context, builder) -> {
                        Minecraft client = Minecraft.getInstance();
                        if (client.getConnection() != null) {
                            List<String> names = new ArrayList<>();
                            for (PlayerInfo info : client.getConnection().getOnlinePlayers()) {
                                names.add(info.getProfile().getName());
                            }
                            return SharedSuggestionProvider.suggest(names, builder);
                        }
                        return builder.buildFuture();
                    })
                    .executes(context -> {
                        String target = StringArgumentType.getString(context, "player");
                        Minecraft client = Minecraft.getInstance();
                        if (client.player == null) return 0;

                        List<String> ignored = loadIgnored(client);
                        boolean removed = ignored.removeIf(name -> name.equalsIgnoreCase(target));

                        if (removed) {
                            saveIgnored(client, ignored);
                            client.player.displayClientMessage(
                                Component.literal("§5✦ §dAmyMail §8» §7Removed §d" + target + " §7from your ignore list."),
                                false
                            );
                        } else {
                            ignored.add(target);
                            saveIgnored(client, ignored);
                            client.player.displayClientMessage(
                                Component.literal("§5✦ §dAmyMail §8» §7Added §d" + target + " §7to your ignore list."),
                                false
                            );
                        }
                        return 1;
                    })
                )
            );
        });

        // 4. Deliver queued messages when recipient joins
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.getConnection() == null) {
                PREVIOUS_ONLINE.clear();
                return;
            }

            Set<String> currentOnline = new HashSet<>();
            for (PlayerInfo info : client.getConnection().getOnlinePlayers()) {
                currentOnline.add(info.getProfile().getName().toLowerCase());
            }

            List<OutboxEntry> queue = loadOutbox(client);
            if (!queue.isEmpty()) {
                boolean changed = false;
                Iterator<OutboxEntry> iterator = queue.iterator();

                while (iterator.hasNext()) {
                    OutboxEntry entry = iterator.next();
                    String recipientLower = entry.recipient.toLowerCase();

                    if (currentOnline.contains(recipientLower)) {
                        if (!PREVIOUS_ONLINE.contains(recipientLower)) {
                            client.player.playSound(SoundEvents.AMETHYST_BLOCK_BREAK, 1.0F, 1.2F);
                            client.player.displayClientMessage(
                                Component.literal("§5✦ §dAmyMail §8» §d" + entry.recipient + " §7is now online!"),
                                false
                            );
                        }

                        sendWhisper(client, entry.recipient, entry.content);
                        client.player.displayClientMessage(
                            Component.literal("§5✦ §dAmyMail §8» §6Delivered to " + entry.recipient + ": §e" + entry.content),
                            false
                        );
                        iterator.remove();
                        changed = true;
                    }
                }

                if (changed) {
                    saveOutbox(client, queue);
                }
            }

            PREVIOUS_ONLINE.clear();
            PREVIOUS_ONLINE.addAll(currentOnline);
        });
        // 5. Handle incoming whispers, ignore list, and mailbox saving
        ClientReceiveMessageEvents.ALLOW_CHAT.register((message, signedMessage, sender, params, timestamp) -> {
            return processIncomingMessage(message.getString());
        });

        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
            if (overlay) return true;
            return processIncomingMessage(message.getString());
        });
    }

    private boolean isPlayerOnline(Minecraft client, String playerName) {
        if (client.getConnection() == null) return false;
        for (PlayerInfo info : client.getConnection().getOnlinePlayers()) {
            if (info.getProfile().getName().equalsIgnoreCase(playerName)) {
                return true;
            }
        }
        return false;
    }

    private void sendWhisper(Minecraft client, String target, String text) {
        if (client.getConnection() != null) {
            client.getConnection().sendCommand("msg " + target + " " + text);
        }
    }

    private boolean processIncomingMessage(String rawText) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return true;

        String lower = rawText.toLowerCase();
        String selfName = client.player.getName().getString().toLowerCase();

        if (rawText.contains("AmyMail") || rawText.startsWith("│") || rawText.startsWith("╭") || rawText.startsWith("╰")) {
            return true;
        }

        List<String> ignored = loadIgnored(client);
        for (String ignoredPlayer : ignored) {
            String igLower = ignoredPlayer.toLowerCase();
            if (lower.startsWith("<" + igLower + ">")
                || lower.startsWith(igLower + " whispers to you:")
                || lower.startsWith("[" + igLower + " ->")
                || lower.startsWith(igLower + " шепчет вам:")) {
                return false;
            }
        }

        if (lower.startsWith("<" + selfName + ">")) {
            return true;
        }

        if (lower.startsWith("you whisper to ") || lower.startsWith("[me -> ") || lower.startsWith("[you -> ")) {
            return false;
        }

        boolean isWhisperToMe = lower.contains("whispers to you:")
            || lower.contains("-> you]")
            || lower.contains("-> " + selfName + "]")
            || lower.contains("шепчет вам:");

        if (isWhisperToMe) {
            client.player.playSound(SoundEvents.AMETHYST_BLOCK_BREAK, 1.0F, 1.0F);

            client.player.displayClientMessage(
                Component.literal("§5✦ §dAmyMail §8» §6" + rawText),
                false
            );

            String ownerUuid = client.player.getUUID().toString();
            String now = LocalDateTime.now().format(TIME_FORMAT);

            List<MailEntry> inbox = loadInbox(client);
            inbox.add(new MailEntry(ownerUuid, now, rawText));
            saveInbox(client, inbox);

            return false;
        }
        return true;
    }

    private Path getPlayerFile(Minecraft client, String prefix) {
        UUID uuid = client.player != null ? client.player.getUUID() : UUID.nameUUIDFromBytes("default".getBytes());
        Path dir = FabricLoader.getInstance().getConfigDir().resolve("amymail");
        try {
            Files.createDirectories(dir);
        } catch (Exception ignored) {}
        return dir.resolve(prefix + "_" + uuid + ".json");
    }

    private List<MailEntry> loadInbox(Minecraft client) {
        Path file = getPlayerFile(client, "mailbox");
        if (!Files.exists(file)) return new ArrayList<>();
        try (Reader reader = Files.newBufferedReader(file)) {
            Type listType = new TypeToken<ArrayList<MailEntry>>() {}.getType();
            List<MailEntry> list = GSON.fromJson(reader, listType);
            return list != null ? list : new ArrayList<>();
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private void saveInbox(Minecraft client, List<MailEntry> inbox) {
        Path file = getPlayerFile(client, "mailbox");
        try (Writer writer = Files.newBufferedWriter(file)) {
            GSON.toJson(inbox, writer);
        } catch (Exception ignored) {}
    }

    private List<OutboxEntry> loadOutbox(Minecraft client) {
        Path file = getPlayerFile(client, "outbox");
        if (!Files.exists(file)) return new ArrayList<>();
        try (Reader reader = Files.newBufferedReader(file)) {
            Type listType = new TypeToken<ArrayList<OutboxEntry>>() {}.getType();
            List<OutboxEntry> list = GSON.fromJson(reader, listType);
            return list != null ? list : new ArrayList<>();
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private void saveOutbox(Minecraft client, List<OutboxEntry> outbox) {
        Path file = getPlayerFile(client, "outbox");
        try (Writer writer = Files.newBufferedWriter(file)) {
            GSON.toJson(outbox, writer);
        } catch (Exception ignored) {}
    }

    private List<String> loadIgnored(Minecraft client) {
        Path file = getPlayerFile(client, "ignored");
        if (!Files.exists(file)) return new ArrayList<>();
        try (Reader reader = Files.newBufferedReader(file)) {
            Type listType = new TypeToken<ArrayList<String>>() {}.getType();
            List<String> list = GSON.fromJson(reader, listType);
            return list != null ? list : new ArrayList<>();
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private void saveIgnored(Minecraft client, List<String> ignored) {
        Path file = getPlayerFile(client, "ignored");
        try (Writer writer = Files.newBufferedWriter(file)) {
            GSON.toJson(ignored, writer);
        } catch (Exception ignoredEx) {}
    }
}
