package com.yourname.bank.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Immutable record of one money movement. */
public record Transaction(long accountNumber, Type type, BigDecimal amount,
                          BigDecimal balanceAfter, LocalDateTime timestamp) {

    public enum Type {
        DEPOSIT, WITHDRAW, TRANSFER_IN, TRANSFER_OUT, INTEREST, LOAN_DISBURSAL, LOAN_REPAYMENT
    }

    @Override
    public String toString() {
        return String.format("%s | %-14s | %10s | balance: %10s",
                timestamp.withNano(0), type, amount, balanceAfter);
    }
}
