package com.tripmate.settlement.service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * upi:// payment links (the NPCI UPI linking format): opening one hands the
 * payment to the payer's own UPI app, prefilled, where they see the account
 * holder's name and approve or cancel it. TripMates is never a party to the
 * payment and learns nothing of it; the payee confirming receipt is what
 * reaches the ledger.
 */
public final class UpiLinks {

    static final String NOTE = "TripMates settle-up";

    private UpiLinks() {
    }

    /**
     * @param vpa         already validated by identity to letters, digits, {@code . _ -} and one {@code @},
     *                    so it goes in as it is
     * @param payeeName   shown by the UPI app; percent-encoded, as it is free text
     * @param amountPaise as rupees with two decimals, which is what UPI apps expect
     */
    public static String payLink(String vpa, String payeeName, long amountPaise) {
        if (amountPaise <= 0) {
            throw new IllegalArgumentException("A payment link needs a positive amount");
        }
        return "upi://pay?pa=" + vpa
                + "&pn=" + encode(payeeName)
                + "&am=" + rupees(amountPaise)
                + "&cu=INR"
                + "&tn=" + encode(NOTE);
    }

    static String rupees(long paise) {
        return (paise / 100) + "." + String.format("%02d", paise % 100);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
