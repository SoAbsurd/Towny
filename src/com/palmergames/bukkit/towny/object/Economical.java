package com.palmergames.bukkit.towny.object;

import com.palmergames.bukkit.config.ConfigNodes;
import com.palmergames.bukkit.towny.TownyEconomyHandler;
import com.palmergames.bukkit.towny.TownyLogger;
import com.palmergames.bukkit.towny.TownySettings;
import com.palmergames.bukkit.towny.exceptions.EconomyException;
import org.bukkit.Bukkit;
import org.bukkit.World;

public interface Economical {
	TownyServerAccount SERVER_ACCOUNT = new TownyServerAccount();
	
	/**
	 * Tries to pay from the players holdings
	 *
	 * @param amount value to deduct from the player's account
	 * @param reason leger memo stating why amount is deducted
	 * @return true if successful
	 * @throws EconomyException if the transaction fails
	 */
	default boolean pay(double amount, String reason) throws EconomyException {
		if (TownySettings.getBoolean(ConfigNodes.ECO_CLOSED_ECONOMY_ENABLED)) {
			return payTo(amount, SERVER_ACCOUNT, reason);
		} else {
			boolean payed = _pay(amount);
			if (payed)
				TownyLogger.logMoneyTransaction(this, amount, null, reason);
			return payed;
		}
	}
	
	default boolean _pay(double amount) throws EconomyException {
		if (canPayFromHoldings(amount)) {
			if (TownyEconomyHandler.isActive())
				if (amount > 0) {
					return TownyEconomyHandler.subtract(getEconomyName(), amount, Bukkit.getWorlds().get(0));
				} else {
					return TownyEconomyHandler.add(getEconomyName(), Math.abs(amount), getBukkitWorld());
				}
		}
		return false;
	}
	
	/**
	 * When collecting money add it to the Accounts bank
	 *
	 * @param amount currency to collect
	 * @param reason memo regarding transaction
	 * @return collected or pay to server account
	 * @throws EconomyException if transaction fails
	 */
	default boolean collect(double amount, String reason) throws EconomyException {
		if (TownySettings.getBoolean(ConfigNodes.ECO_CLOSED_ECONOMY_ENABLED)) {
			return SERVER_ACCOUNT.payTo(amount, this, reason);
		} else {
			boolean collected = _collect(amount);
			if (collected)
				TownyLogger.logMoneyTransaction(null, amount, this, reason);
			return collected;
		}
	}
	
	default boolean _collect(double amount) throws EconomyException {
		return TownyEconomyHandler.add(getEconomyName(), amount, getBukkitWorld());
	}
	
	/**
	 * When one account is paying another account(Taxes/Plot Purchasing)
	 *
	 * @param amount    currency to be collected
	 * @param collector recipient of transaction
	 * @param reason    memo regarding transaction
	 * @return true if successfully payed amount to collector.
	 * @throws EconomyException if transaction fails
	 */
	default boolean payTo(double amount, Economical collector, String reason) throws EconomyException {
		boolean payed = _payTo(amount, collector);
		if (payed)
			TownyLogger.logMoneyTransaction(this, amount, collector, reason);
		return payed;
	}
	
	default boolean _payTo(double amount, Economical collector) throws EconomyException {
		if (_pay(amount)) {
			if (!collector._collect(amount)) {
				_collect(amount); //Transaction failed. Refunding amount.
				return false;
			} else {
				return true;
			}
		} else {
			return false;
		}
	}
	
	/**
	 * Fetch the current world for this object
	 *
	 * @return Bukkit world for the object
	 */
	World getBukkitWorld();
	
	/**
	 * Get a valid economy account name for this object.
	 *
	 * @return account name
	 */
	String getEconomyName();
	
	final class TownyServerAccount implements Economical {
		private String name = TownySettings.getString(ConfigNodes.ECO_CLOSED_ECONOMY_SERVER_ACCOUNT);
		TownyServerAccount() {
			super();
		}
		
		@Override
		public World getBukkitWorld() {
			return Bukkit.getWorlds().get(0);
		}
		
		@Override
		public String getEconomyName() {
			return name;
		}
	}
	
	/**
	 * Set balance and log this action
	 *
	 * @param amount currency to transact
	 * @param reason memo regarding transaction
	 * @return true, or pay/collect balance for given reason
	 * @throws EconomyException if transaction fails
	 */
	default boolean setBalance(double amount, String reason) throws EconomyException {
		double balance = getHoldingBalance();
		double diff = amount - balance;
		if (diff > 0) {
			// Adding to
			return collect(diff, reason);
		} else if (balance > amount) {
			// Subtracting from
			diff = -diff;
			return pay(diff, reason);
		} else {
			// Same amount, do nothing.
			return true;
		}
	}

	/*
	private boolean _setBalance(double amount) {
		return TownyEconomyHandler.setBalance(getEconomyName(), amount, getBukkitWorld());
	}
	*/
	
	default double getHoldingBalance() throws EconomyException {
		try {
			return TownyEconomyHandler.getBalance(getEconomyName(), getBukkitWorld());
		} catch (NoClassDefFoundError e) {
			e.printStackTrace();
			throw new EconomyException("Economy error getting holdings for " + getEconomyName());
		}
	}
	
	/**
	 * Does this object have enough in it's economy account to pay?
	 *
	 * @param amount currency to check for
	 * @return true if there is enough.
	 * @throws EconomyException if failure
	 */
	default boolean canPayFromHoldings(double amount) throws EconomyException {
		return TownyEconomyHandler.hasEnough(getEconomyName(), amount, getBukkitWorld());
	}
	
	/**
	 * Used To Get Balance of Players holdings in String format for printing
	 *
	 * @return current account balance formatted in a string.
	 */
	default String getHoldingFormattedBalance() {
		try {
			return TownyEconomyHandler.getFormattedBalance(getHoldingBalance());
		} catch (EconomyException e) {
			return "Error Accessing Bank Account";
		}
	}
	
	/**
	 * Attempt to delete the economy account.
	 */
	default void removeAccount() {
		TownyEconomyHandler.removeAccount(getEconomyName());
	}
}