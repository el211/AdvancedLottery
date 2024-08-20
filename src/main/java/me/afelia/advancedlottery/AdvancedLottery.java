package me.afelia.advancedlottery;

import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

public class AdvancedLottery extends JavaPlugin implements Listener {

    private Location lotteryLocation;
    private Map<String, Reward> rewards = new HashMap<>();
    private Material ticketItem;
    private int ticketCustomModelData;
    private NamespacedKey lotteryTicketKey;
    private boolean applyBlindness; // Variable to store whether blindness effect should be applied

    private Map<UUID, List<PotionEffect>> playerEffects = new HashMap<>();
    private Map<UUID, Boolean> playerInAnimation = new HashMap<>(); // Track players in animation mode

    @Override
    public void onEnable() {
        // Load configuration
        this.saveDefaultConfig();

        // Initialize the NamespacedKey for the custom NBT tag
        lotteryTicketKey = new NamespacedKey(this, "lottery_ticket");

        // Load lottery location from config
        ConfigurationSection placeConfig = getConfig().getConfigurationSection("place-player-teleports");
        lotteryLocation = new Location(
                Bukkit.getWorld(placeConfig.getString("world")),
                placeConfig.getDouble("x"),
                placeConfig.getDouble("y"),
                placeConfig.getDouble("z"),
                (float) placeConfig.getDouble("yaw"),
                (float) placeConfig.getDouble("pitch")
        );

        // Load rewards from config
        ConfigurationSection rewardsConfig = getConfig().getConfigurationSection("Rewards");
        for (String key : rewardsConfig.getKeys(false)) {
            ConfigurationSection rewardConfig = rewardsConfig.getConfigurationSection(key);
            String command = rewardConfig.getString("command");
            String message = ChatColor.translateAlternateColorCodes('&', rewardConfig.getString("message-after-teleport-back"));
            double chanceRate = rewardConfig.getDouble("chance-rate", 1.0); // Default chance rate is 1.0 (100%)
            rewards.put(key, new Reward(command, message, chanceRate));
        }

        // Load ticket item configuration from config
        ConfigurationSection ticketConfig = getConfig().getConfigurationSection("Ticket-items");
        ticketItem = Material.valueOf(ticketConfig.getString("item"));
        ticketCustomModelData = ticketConfig.getInt("custom-model-data");
        applyBlindness = ticketConfig.getBoolean("blindness-effect"); // Load applyBlindness value from config

        // Register events
        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (label.equalsIgnoreCase("advancedlottery")) {
            if (args.length == 1) {
                if (args[0].equalsIgnoreCase("get")) {
                    if (sender instanceof Player) {
                        Player player = (Player) sender;
                        player.getInventory().addItem(createLotteryTicket());
                        player.sendMessage("You received a lottery ticket!");
                        return true;
                    } else {
                        sender.sendMessage("Only players can use this command.");
                        return false;
                    }
                } else if (args[0].equalsIgnoreCase("reload")) {
                    if (sender.hasPermission("advancedlottery.reload")) {
                        reloadConfig();
                        sender.sendMessage("Config reloaded.");
                        return true;
                    } else {
                        sender.sendMessage("You don't have permission to use this command.");
                        return false;
                    }
                }
            } else if (args.length == 2) {
                if (args[0].equalsIgnoreCase("give")) {
                    if (sender.hasPermission("advancedlottery.give")) {
                        Player target = Bukkit.getPlayer(args[1]);
                        if (target != null) {
                            target.getInventory().addItem(createLotteryTicket());
                            sender.sendMessage("Gave a lottery ticket to " + target.getName());
                            return true;
                        } else {
                            sender.sendMessage("Player not found.");
                            return false;
                        }
                    } else {
                        sender.sendMessage("You don't have permission to use this command.");
                        return false;
                    }
                }
            }
        }
        return false;
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        ItemStack item = event.getItem();
        if (!isLotteryTicket(item)) {
            return;
        }

        Player player = event.getPlayer();
        Location originalLocation = player.getLocation();

        // Remove the lottery ticket from player's inventory
        if (item.getAmount() == 1) {
            player.getInventory().remove(item);
        } else {
            item.setAmount(item.getAmount() - 1);
        }

        // Teleport player to lottery location and apply effects
        player.teleport(lotteryLocation);
        player.setGameMode(GameMode.SPECTATOR);

        // Check if blindness effect should be applied
        if (applyBlindness) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 200, 1, false, false));
        }
        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW, 200, 255, false, false));

        // Set player in animation mode
        playerInAnimation.put(player.getUniqueId(), true);

        // Perform lottery animation
        performLotteryAnimation(player, originalLocation);
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        // Check if the player is in animation mode and in spectator mode
        if (player.getGameMode() == GameMode.SPECTATOR && playerInAnimation.getOrDefault(player.getUniqueId(), false)) {
            event.setCancelled(true); // Prevent movement only if they are in animation mode
        }
    }


    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        if (playerInAnimation.containsKey(player.getUniqueId()) && playerInAnimation.get(player.getUniqueId())) {
            // Cancel the chat event if the player is in animation mode
            event.setCancelled(true);
            player.sendMessage(ChatColor.RED + "You cannot use chat during the animation!");
        }
    }

    private void grantReward(Player player) {
        // Get a random reward from the rewards map
        Reward reward = getRandomReward();

        // Execute the reward command
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), reward.getCommand().replace("%player%", player.getName()));

        // Send message to player
        player.sendMessage(reward.getMessageAfterTeleportBack());
    }

    private Reward getRandomReward() {
        // Get total chance rate
        double totalChanceRate = rewards.values().stream().mapToDouble(Reward::getChanceRate).sum();

        // Generate a random value between 0 and totalChanceRate
        double randomValue = new Random().nextDouble() * totalChanceRate;

        // Iterate through rewards and select the one with a chance rate that covers the random value
        double cumulativeChance = 0;
        for (Reward reward : rewards.values()) {
            cumulativeChance += reward.getChanceRate();
            if (randomValue <= cumulativeChance) {
                return reward;
            }
        }

        // In case of no reward is selected, return null
        return null;
    }

    private void performLotteryAnimation(final Player player, final Location originalLocation) {
        List<PotionEffect> appliedEffects = new ArrayList<>();

        new BukkitRunnable() {
            int tick = 0;

            @Override
            public void run() {
                if (tick >= 100) {
                    // Restore player's state and teleport back
                    player.setGameMode(GameMode.SURVIVAL);
                    player.teleport(originalLocation);

                    // Remove potion effects applied during the animation
                    for (PotionEffect effect : appliedEffects) {
                        player.removePotionEffect(effect.getType());
                    }

                    // Remove slowness effect
                    player.removePotionEffect(PotionEffectType.SLOW);

                    // Reset player attributes
                    player.setAllowFlight(false);
                    player.setFlying(false);
                    player.setCanPickupItems(true);
                    player.setInvulnerable(false);

                    // Grant reward
                    grantReward(player);

                    // Remove player from animation mode
                    playerInAnimation.remove(player.getUniqueId());

                    cancel();
                    return;
                }

                // Your animation logic goes here
                player.sendTitle("Rolling Ticket...", String.valueOf(new Random().nextInt(100)), 0, 20, 0);

                // Play the sound effect
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, SoundCategory.MASTER, 1, 1);

                tick++;
            }

            @Override
            public synchronized void cancel() throws IllegalStateException {
                super.cancel();
                // Remove stored potion effects
                playerEffects.remove(player.getUniqueId(), appliedEffects);
            }
        }.runTaskTimer(this, 0L, 1L);

        // Store potion effects applied during animation
        appliedEffects.add(new PotionEffect(PotionEffectType.BLINDNESS, 200, 1, false, false)); // Example effect
        playerEffects.put(player.getUniqueId(), appliedEffects);
    }

    private class Reward {
        private String command;
        private String messageAfterTeleportBack;
        private double chanceRate;

        public Reward(String command, String messageAfterTeleportBack, double chanceRate) {
            this.command = command;
            this.messageAfterTeleportBack = messageAfterTeleportBack;
            this.chanceRate = chanceRate;
        }

        public String getCommand() {
            return command;
        }

        public String getMessageAfterTeleportBack() {
            return messageAfterTeleportBack;
        }

        public double getChanceRate() {
            return chanceRate;
        }
    }

    private ItemStack createLotteryTicket() {
        ItemStack ticket = new ItemStack(ticketItem);
        ItemMeta meta = ticket.getItemMeta();
        meta.setCustomModelData(ticketCustomModelData);
        meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', getConfig().getString("Ticket-items.item-name")));

        // Add a custom NBT tag to differentiate the lottery ticket
        meta.getPersistentDataContainer().set(lotteryTicketKey, PersistentDataType.BYTE, (byte) 1);

        ticket.setItemMeta(meta);
        return ticket;
    }

    private boolean isLotteryTicket(ItemStack item) {
        // Check if the item has the custom NBT tag indicating it's a lottery ticket
        return item != null && item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(lotteryTicketKey, PersistentDataType.BYTE);
    }
}
