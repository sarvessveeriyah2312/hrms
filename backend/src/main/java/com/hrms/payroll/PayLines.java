package com.hrms.payroll;

import com.hrms.shared.web.ApiException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * Reads the pay lines HR exports from their payroll software, to generate payslips. A CSV with a header row and the
 * columns employee_no, type, item, amount, plus optional quantity and rate (in any order; other columns are ignored):
 * <pre>
 * employee_no,type,item,amount,quantity,rate
 * E0001,BASIC,Basic pay,3000.00,31,3000
 * E0001,ALLOWANCE,Meal,93.00,,
 * E0001,OVERTIME,Normal OT,86.54,4,21.64
 * E0001,DEDUCTION,Employee EPF,319.00,,
 * E0001,EMPLOYER,Employer EPF,377.00,,
 * </pre>
 * type is BASIC, ALLOWANCE or OVERTIME (earnings), DEDUCTION, or EMPLOYER (employer contributions, shown but not
 * deducted). EARNING is also accepted: it counts as basic pay when the item says "basic", overtime when it says "OT" or
 * "overtime", otherwise an allowance. quantity is overtime hours (or basic days) and rate the hourly (or monthly) rate.
 * Everything is checked before anything is generated; the errors (up to 20) say which line is wrong.
 */
final class PayLines {

    static final int MAX_BYTES = 1024 * 1024;
    static final int MAX_LINES = 10_000;
    static final int MAX_PER_PERSON = 40;
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("99999999.99");

    private PayLines() {
    }

    /** Lines by employee number (as written in the file), in file order. */
    static Map<String, List<PayslipPdf.Line>> parse(String csv) {
        if (csv == null || csv.isBlank()) throw bad("CSV_REQUIRED", "Choose the pay lines file (CSV).", List.of());
        if (csv.length() > MAX_BYTES) throw bad("CSV_TOO_LARGE", "The pay lines file can be up to 1 MB.", List.of());
        String text = csv.startsWith("﻿") ? csv.substring(1) : csv;
        List<List<String>> rows = rows(text);
        if (rows.isEmpty()) throw bad("CSV_REQUIRED", "The file is empty.", List.of());
        List<String> header = rows.get(0).stream().map(h -> h.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "")).toList();
        int no = column(header, "employeeno", "employeenumber", "empno", "staffno");
        int type = column(header, "type", "kind");
        int item = column(header, "item", "description", "label");
        int amount = column(header, "amount", "value");
        int quantity = column(header, "quantity", "qty", "hours", "days");
        int rateCol = column(header, "rate", "hourlyrate", "unitrate");
        if (no < 0 || type < 0 || item < 0 || amount < 0) {
            throw bad("CSV_HEADER", "The first row must name the columns: employee_no, type, item, amount.", List.of());
        }
        if (rows.size() - 1 > MAX_LINES) throw bad("CSV_TOO_LONG", "Up to " + MAX_LINES + " lines at a time.", List.of());

        Map<String, List<PayslipPdf.Line>> out = new LinkedHashMap<>();
        Map<String, String> canonical = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        for (int i = 1; i < rows.size(); i++) {
            List<String> r = rows.get(i);
            if (r.stream().allMatch(String::isBlank)) continue;
            int lineNo = i + 1;
            String emp = cell(r, no);
            String kind = cell(r, type).toUpperCase(Locale.ROOT);
            String label = cell(r, item);
            String raw = cell(r, amount).replace(",", "");
            if (emp.isEmpty()) { errors.add("Line " + lineNo + ": the employee number is missing."); continue; }
            String category = switch (kind) {
                case "BASIC", "BASIC PAY", "B" -> "BASIC";
                case "ALLOWANCE", "ALLOWANCES", "A" -> "ALLOWANCE";
                case "OVERTIME", "OT" -> "OVERTIME";
                case "EARNING", "EARNINGS", "E" -> categoryOf(label);
                default -> null;
            };
            kind = category != null ? "EARNING" : switch (kind) {
                case "DEDUCTION", "DEDUCTIONS", "D" -> "DEDUCTION";
                case "EMPLOYER", "EMPLOYER CONTRIBUTION", "C" -> "EMPLOYER";
                default -> null;
            };
            if (kind == null) { errors.add("Line " + lineNo + ": type must be BASIC, ALLOWANCE, OVERTIME, EARNING, DEDUCTION or EMPLOYER."); continue; }
            if (label.isEmpty()) { errors.add("Line " + lineNo + ": the item name is missing."); continue; }
            if (label.length() > 80) { errors.add("Line " + lineNo + ": the item name can be up to 80 characters."); continue; }
            BigDecimal value;
            try {
                value = new BigDecimal(raw);
            } catch (NumberFormatException e) {
                errors.add("Line " + lineNo + ": the amount isn't a number.");
                continue;
            }
            if (value.signum() < 0 || value.compareTo(MAX_AMOUNT) > 0 || value.scale() > 2) {
                errors.add("Line " + lineNo + ": the amount must be between 0 and 99,999,999.99 with at most 2 decimals.");
                continue;
            }
            BigDecimal qty;
            BigDecimal rate;
            try {
                qty = optional(cell(r, quantity));
                rate = optional(cell(r, rateCol));
            } catch (NumberFormatException e) {
                errors.add("Line " + lineNo + ": quantity and rate must be numbers.");
                continue;
            }
            if ((qty != null && (qty.signum() < 0 || qty.compareTo(new BigDecimal("99999")) > 0))
                || (rate != null && (rate.signum() < 0 || rate.compareTo(MAX_AMOUNT) > 0))) {
                errors.add("Line " + lineNo + ": quantity and rate can't be negative or that large.");
                continue;
            }
            String key = canonical.computeIfAbsent(emp.toUpperCase(Locale.ROOT), k -> emp);
            List<PayslipPdf.Line> lines = out.computeIfAbsent(key, k -> new ArrayList<>());
            if (lines.size() >= MAX_PER_PERSON) { errors.add("Line " + lineNo + ": up to " + MAX_PER_PERSON + " lines per person."); continue; }
            lines.add(new PayslipPdf.Line(kind, category, label, value,
                qty == null ? null : qty.setScale(2, java.math.RoundingMode.HALF_UP), rate == null ? null : rate.setScale(4, java.math.RoundingMode.HALF_UP)));
            if (errors.size() >= 20) break;
        }
        if (!errors.isEmpty()) throw bad("CSV_ERRORS", "Some lines need fixing. Nothing was generated.", errors);
        if (out.isEmpty()) throw bad("CSV_REQUIRED", "The file has no pay lines.", List.of());
        for (var e : out.entrySet()) {
            if (e.getValue().stream().noneMatch(l -> l.kind().equals("EARNING"))) {
                errors.add(e.getKey() + ": has no earnings.");
            } else if (PayslipPdf.total(e.getValue(), "DEDUCTION").compareTo(PayslipPdf.total(e.getValue(), "EARNING")) > 0) {
                errors.add(e.getKey() + ": deductions are more than earnings.");
            }
        }
        if (!errors.isEmpty()) throw bad("CSV_ERRORS", "Some payslips need fixing. Nothing was generated.", errors.stream().limit(20).toList());
        return out;
    }

    /** Splits CSV text into rows of cells, with quoted cells ("a, b" and "" for a quote). */
    static List<List<String>> rows(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') { cell.append('"'); i++; }
                else if (c == '"') quoted = false;
                else cell.append(c);
            } else if (c == '"' && cell.isEmpty()) {
                quoted = true;
            } else if (c == ',') {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                row.add(cell.toString());
                cell.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else {
                cell.append(c);
            }
        }
        if (!cell.isEmpty() || !row.isEmpty()) {
            row.add(cell.toString());
            rows.add(row);
        }
        return rows;
    }

    /** For an earning typed as EARNING: basic pay when the item says "basic", overtime for "OT"/"overtime", else an allowance. */
    static String categoryOf(String label) {
        String l = label == null ? "" : label.toLowerCase(Locale.ROOT);
        if (l.contains("basic")) return "BASIC";
        if (l.matches(".*\\b(ot|overtime)\\b.*")) return "OVERTIME";
        return "ALLOWANCE";
    }

    private static BigDecimal optional(String raw) {
        String v = raw.replace(",", "").trim();
        return v.isEmpty() ? null : new BigDecimal(v);
    }

    private static int column(List<String> header, String... names) {
        for (String n : names) {
            int i = header.indexOf(n);
            if (i >= 0) return i;
        }
        return -1;
    }

    private static String cell(List<String> row, int i) {
        return i >= 0 && i < row.size() ? row.get(i).trim() : "";
    }

    private static ApiException bad(String code, String message, List<String> errors) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message).with("errors", errors);
    }
}
