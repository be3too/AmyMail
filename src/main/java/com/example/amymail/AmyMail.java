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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
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
import java.util.Locale;

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
                                sender.sendSystemMessage(Component.literal(tr(sender, "self_error")));
                                return 0;
                            }

                            ServerPlayer onlineTarget = server.getPlayerList().getPlayerByName(targetName);
                            String now = LocalDateTime.now().format(TIME_FORMAT);

                            if (onlineTarget != null) {
                                if (isIgnoredBy(onlineTarget.getUUID().toString(), sender.getName().getString())) {
                                    sender.sendSystemMessage(Component.literal(tr(sender, "ignored_error")));
                                    return 0;
                                }

                                playAmethystSound(onlineTarget, 1.0F);
                                onlineTarget.sendSystemMessage(
                                    Component.literal(tr(onlineTarget, "receive_online", sender.getName().getString(), msg))
                                );

                                sender.sendSystemMessage(
                                    Component.literal(tr(sender, "sent_online", onlineTarget.getName().getString(), msg))
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
                                    Component.literal(tr(sender, "sent_offline", targetName, msg))
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
                        player.sendSystemMessage(Component.literal(tr(player, "mailbox_empty")));
                        return 1;
                    }

                    player.sendSystemMessage(
                        Component.literal(tr(player, "mailbox_header", player.getName().getString()))
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

                        player.sendSystemMessage(Component.literal(tr(player, "mailbox_cleared")));
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
                            player.sendSystemMessage(Component.literal(tr(player, "ignore_empty")));
                        } else {
                            player.sendSystemMessage(
                                Component.literal(tr(player, "ignore_list", String.join("§7, §d", ignored)))
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
                            player.sendSystemMessage(Component.literal(tr(player, "ignore_removed", target)));
                        } else {
                            ignored.add(target);
                            saveIgnored(uuid, ignored);
                            player.sendSystemMessage(Component.literal(tr(player, "ignore_added", target)));
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
                playAmethystSound(player, 1.2F);
                player.sendSystemMessage(
                    Component.literal(tr(player, "offline_received", String.valueOf(pending.size())))
                );

                for (MailEntry mail : pending) {
                    player.sendSystemMessage(
                        Component.literal("§5│ §8[" + mail.timestamp + "] §6" + mail.senderName + "§8: §6" + mail.content)
                    );
                }
            }
        });
    }

    private String getLangGroup(ServerPlayer player) {
        String lang = player.clientInformation().language();
        if (lang == null) return "en";
        String lower = lang.toLowerCase(Locale.ROOT);

        // Russian & CIS languages
        if (lower.startsWith("ru") || lower.startsWith("be") || lower.startsWith("uk")
            || lower.startsWith("kk") || lower.startsWith("uz") || lower.startsWith("ky")
            || lower.startsWith("tg") || lower.startsWith("az") || lower.startsWith("hy")
            || lower.startsWith("ka") || lower.startsWith("mo") || lower.startsWith("tk")) {
            return "ru";
        }
        // Spanish (Spain, Mexico, Latin America)
        if (lower.startsWith("es")) {
            return "es";
        }
        // French (France, Canada, Belgium, Switzerland, Francophone regions)
        if (lower.startsWith("fr")) {
            return "fr";
        }
        // Chinese (Simplified & Traditional)
        if (lower.startsWith("zh")) {
            return "zh";
        }
        return "en";
    }
    private String tr(ServerPlayer player, String key, String... args) {
        String lang = getLangGroup(player);
        String a1 = args.length > 0 ? args[0] : "";
        String a2 = args.length > 1 ? args[1] : "";

        return switch (lang) {
            case "ru" -> switch (key) {
                case "self_error" -> "§5✦ §dAmyMail §8» §7Нельзя отправить письмо самому себе.";
                case "ignored_error" -> "§5✦ §dAmyMail §8» §7Этот игрок добавил вас в чёрный список.";
                case "receive_online" -> "§5✦ §dAmyMail §8» §6" + a1 + " -> Вам: §6" + a2;
                case "sent_online" -> "§5✦ §dAmyMail §8» §6Вы -> " + a1 + ": §e" + a2;
                case "sent_offline" -> "§5✦ §dAmyMail §8» §6Сохранено для " + a1 + " (оффлайн): §e" + a2;
                case "mailbox_empty" -> "§5✦ §dAmyMail §8» §7Ваш личный почтовый ящик пуст.";
                case "mailbox_header" -> "§5╭─── §d✦ AmyMail §8• §7Почта игрока §d" + a1 + " §5───";
                case "mailbox_cleared" -> "§5✦ §dAmyMail §8» §7Ваш почтовый ящик очищен.";
                case "ignore_empty" -> "§5✦ §dAmyMail §8» §7Ваш чёрный список пуст.";
                case "ignore_list" -> "§5✦ §dAmyMail §8» §7Игнорируемые игроки: §d" + a1;
                case "ignore_removed" -> "§5✦ §dAmyMail §8» §7Игрок §d" + a1 + " §7удалён из чёрного списка.";
                case "ignore_added" -> "§5✦ §dAmyMail §8» §7Игрок §d" + a1 + " §7добавлен в чёрный список.";
                case "offline_received" -> "§5✦ §dAmyMail §8» §7Новых писем, пока вас не было (§d" + a1 + "§7):";
                default -> "";
            };
            case "es" -> switch (key) {
                case "self_error" -> "§5✦ §dAmyMail §8» §7No puedes enviarte correo a ti mismo.";
                case "ignored_error" -> "§5✦ §dAmyMail §8» §7Este jugador está ignorando tus mensajes.";
                case "receive_online" -> "§5✦ §dAmyMail §8» §6" + a1 + " -> Tú: §6" + a2;
                case "sent_online" -> "§5✦ §dAmyMail §8» §6Tú -> " + a1 + ": §e" + a2;
                case "sent_offline" -> "§5✦ §dAmyMail §8» §6En cola para " + a1 + " (desconectado): §e" + a2;
                case "mailbox_empty" -> "§5✦ §dAmyMail §8» §7Tu buzón privado está vacío.";
                case "mailbox_header" -> "§5╭─── §d✦ AmyMail §8• §7Buzón de §d" + a1 + " §5───";
                case "mailbox_cleared" -> "§5✦ §dAmyMail §8» §7Tu buzón ha sido vaciado.";
                case "ignore_empty" -> "§5✦ §dAmyMail §8» §7Tu lista de ignorados está vacía.";
                case "ignore_list" -> "§5✦ §dAmyMail §8» §7Jugadores ignorados: §d" + a1;
                case "ignore_removed" -> "§5✦ §dAmyMail §8» §7Se quitó a §d" + a1 + " §7de tu lista de ignorados.";
                case "ignore_added" -> "§5✦ §dAmyMail §8» §7Se añadió a §d" + a1 + " §7a tu lista de ignorados.";
                case "offline_received" -> "§5✦ §dAmyMail §8» §7Recibiste §d" + a1 + " §7mensaje(s) mientras estabas desconectado:";
                default -> "";
            };
            case "fr" -> switch (key) {
                case "self_error" -> "§5✦ §dAmyMail §8» §7Vous ne pouvez pas vous envoyer de courrier.";
                case "ignored_error" -> "§5✦ §dAmyMail §8» §7Ce joueur ignore vos messages.";
                case "receive_online" -> "§5✦ §dAmyMail §8» §6" + a1 + " -> Vous : §6" + a2;
                case "sent_online" -> "§5✦ §dAmyMail §8» §6Vous -> " + a1 + " : §e" + a2;
                case "sent_offline" -> "§5✦ §dAmyMail §8» §6En attente pour " + a1 + " (hors ligne) : §e" + a2;
                case "mailbox_empty" -> "§5✦ §dAmyMail §8» §7Votre boîte aux lettres privée est vide.";
                case "mailbox_header" -> "§5╭─── §d✦ AmyMail §8• §7Boîte de §d" + a1 + " §5───";
                case "mailbox_cleared" -> "§5✦ §dAmyMail §8» §7Votre boîte aux lettres a été vidée.";
                case "ignore_empty" -> "§5✦ §dAmyMail §8» §7Votre liste d'ignorés est vide.";
                case "ignore_list" -> "§5✦ §dAmyMail §8» §7Joueurs ignorés : §d" + a1;
                case "ignore_removed" -> "§5✦ §dAmyMail §8» §d" + a1 + " §7a été retiré de votre liste d'ignorés.";
                case "ignore_added" -> "§5✦ §dAmyMail §8» §d" + a1 + " §7a été ajouté à votre liste d'ignorés.";
                case "offline_received" -> "§5✦ §dAmyMail §8» §7Vous avez reçu §d" + a1 + " §7message(s) hors ligne :";
                default -> "";
            };
            case "zh" -> switch (key) {
                case "self_error" -> "§5✦ §dAmyMail §8» §7你不能给自己发送邮件。";
                case "ignored_error" -> "§5✦ §dAmyMail §8» §7该玩家已屏蔽你的消息。";
                case "receive_online" -> "§5✦ §dAmyMail §8» §6" + a1 + " -> 你: §6" + a2;
                case "sent_online" -> "§5✦ §dAmyMail §8» §6你 -> " + a1 + ": §e" + a2;
                case "sent_offline" -> "§5✦ §dAmyMail §8» §6已为 " + a1 + " (离线) 暂存消息: §e" + a2;
                case "mailbox_empty" -> "§5✦ §dAmyMail §8» §7你的私人邮箱是空的。";
                case "mailbox_header" -> "§5╭─── §d✦ AmyMail §8• §d" + a1 + " §7的邮箱 §5───";
                case "mailbox_cleared" -> "§5✦ §dAmyMail §8» §7你的邮箱已清空。";
                case "ignore_empty" -> "§5✦ §dAmyMail §8» §7你的屏蔽列表为空。";
                case "ignore_list" -> "§5✦ §dAmyMail §8» §7已屏蔽的玩家: §d" + a1;
                case "ignore_removed" -> "§5✦ §dAmyMail §8» §7已将 §d" + a1 + " §7移出屏蔽列表。";
                case "ignore_added" -> "§5✦ §dAmyMail §8» §7已将 §d" + a1 + " §7加入屏蔽列表。";
                case "offline_received" -> "§5✦ §dAmyMail §8» §7你离线期间收到了 §d" + a1 + " §7条新消息:";
                default -> "";
            };
            default -> switch (key) {
                case "self_error" -> "§5✦ §dAmyMail §8» §7You cannot send mail to yourself.";
                case "ignored_error" -> "§5✦ §dAmyMail §8» §7This player is ignoring your messages.";
                case "receive_online" -> "§5✦ §dAmyMail §8» §6" + a1 + " -> You: §6" + a2;
                case "sent_online" -> "§5✦ §dAmyMail §8» §6You -> " + a1 + ": §e" + a2;
                case "sent_offline" -> "§5✦ §dAmyMail §8» §6Queued for " + a1 + " (offline): §e" + a2;
                case "mailbox_empty" -> "§5✦ §dAmyMail §8» §7Your private mailbox is empty.";
                case "mailbox_header" -> "§5╭─── §d✦ AmyMail §8• §7Inbox for §d" + a1 + " §5───";
                case "mailbox_cleared" -> "§5✦ §dAmyMail §8» §7Your mailbox has been cleared.";
                case "ignore_empty" -> "§5✦ §dAmyMail §8» §7Your ignore list is empty.";
                case "ignore_list" -> "§5✦ §dAmyMail §8» §7Ignored players: §d" + a1;
                case "ignore_removed" -> "§5✦ §dAmyMail §8» §7Removed §d" + a1 + " §7from your ignore list.";
                case "ignore_added" -> "§5✦ §dAmyMail §8» §7Added §d" + a1 + " §7to your ignore list.";
                case "offline_received" -> "§5✦ §dAmyMail §8» §7You received §d" + a1 + " §7new message(s) while offline:";
                default -> "";
            };
        };
    }

    private void playAmethystSound(ServerPlayer player, float pitch) {
        player.connection.send(new ClientboundSoundPacket(
            BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.AMETHYST_BLOCK_BREAK),
            SoundSource.MASTER,
            player.getX(),
            player.getY(),
            player.getZ(),
            1.0F,
            pitch,
            player.level().getRandom().nextLong()
        ));
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
