package com.auction.client.util;

import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * Tiện ích gắn format tiền VNĐ (có dấu phẩy) vào TextField.
 *
 * Cách dùng:
 *   MoneyField.attach(myTextField);
 *   double value = MoneyField.getValue(myTextField); // lấy số thực
 *
 * Người dùng gõ:  5000000  → hiển thị: 5,000,000
 * Người dùng gõ:  1200000  → hiển thị: 1,200,000
 */
public class MoneyField {

    private static final DecimalFormat FMT;

    static {
        DecimalFormatSymbols sym = new DecimalFormatSymbols(Locale.US);
        sym.setGroupingSeparator(',');
        sym.setDecimalSeparator('.');
        FMT = new DecimalFormat("#,###", sym);
    }

    /**
     * Gắn TextFormatter tự động thêm dấu phẩy khi gõ số.
     * Chỉ cho phép chữ số — không cho phép ký tự khác.
     */
    public static void attach(TextField field) {
        // Formatter: chỉ nhận chữ số, tự format phẩy sau mỗi keystroke
        field.setTextFormatter(new TextFormatter<>(change -> {
            String newText = change.getControlNewText().replace(",", "");
            if (!newText.matches("\\d*")) return null; // chặn ký tự không phải số
            return change;
        }));

        // Listener: re-format sau mỗi thay đổi
        field.textProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal == null || newVal.isEmpty()) return;

            String digits = newVal.replace(",", "");
            if (digits.isEmpty()) return;

            try {
                long number = Long.parseLong(digits);
                String formatted = FMT.format(number);
                if (!formatted.equals(newVal)) {
                    int caretPos = field.getCaretPosition();
                    // Đếm số phẩy trước và sau để điều chỉnh caret
                    long commasBefore = newVal.chars().filter(c -> c == ',').count();
                    long commasAfter  = formatted.chars().filter(c -> c == ',').count();
                    field.setText(formatted);
                    int newCaret = caretPos + (int)(commasAfter - commasBefore);
                    field.positionCaret(Math.max(0, Math.min(newCaret, formatted.length())));
                }
            } catch (NumberFormatException ignored) {}
        });
    }

    /**
     * Lấy giá trị số từ TextField đã gắn MoneyField.
     * Trả về -1 nếu rỗng hoặc không hợp lệ.
     */
    public static double getValue(TextField field) {
        String text = field.getText().replace(",", "").trim();
        if (text.isEmpty()) return -1;
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
