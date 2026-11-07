package com.hrms.employee;

import com.hrms.shared.mail.SecretBox;
import com.hrms.shared.web.ApiException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * The profile's sections and their fields: what each holds, what's required, and which values are sensitive (stored
 * encrypted). The web builds its forms from {@link #specs()}, so the two never disagree.
 *
 * <p>Object sections hold one set of fields; list sections ({@code list = true}) hold {@code {"items": [...]}}. A field
 * marked required is only required once anything in that object (or item) is filled in, so a section can be cleared.
 * Private sections are seen only by the person and by people whose {@code employee:manage} scope covers them;
 * the others by anyone whose {@code employee:view} scope covers them.
 */
@Component
class ProfileSections {

    enum Type { TEXT, EMAIL, PHONE, DATE, YEAR, SELECT }

    record Field(String key, Type type, boolean required, int max, List<String> options, boolean sensitive) {
    }

    record Section(String key, boolean list, int maxItems, boolean isPrivate, List<Field> fields) {
    }

    static final String ITEMS = "items";
    private static final String SEALED = "enc:";
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern PHONE = Pattern.compile("^\\+?[0-9 ()-]{6,20}$");
    private static final Pattern YEAR = Pattern.compile("^(19|20)\\d{2}$");
    private static final List<String> YES_NO = List.of("YES", "NO");
    private static final List<String> GENDERS = List.of("MALE", "FEMALE");

    private static final List<Section> SECTIONS = List.of(
        new Section("personal", false, 1, true, List.of(
            text("preferredName", 80),
            select("gender", GENDERS),
            date("dateOfBirth"),
            text("nationality", 60),
            select("maritalStatus", List.of("SINGLE", "MARRIED", "DIVORCED", "WIDOWED")),
            text("religion", 60),
            text("race", 60),
            select("idType", List.of("MYKAD", "NRIC", "FIN", "HKID", "AADHAAR", "PASSPORT", "OTHER")),
            secret("idNumber", 40),
            secret("passportNumber", 40),
            date("passportExpiry"),
            secret("taxNumber", 40),
            secret("epfNumber", 40),
            secret("socsoNumber", 40))),
        new Section("contact", false, 1, false, List.of(
            new Field("personalEmail", Type.EMAIL, false, 120, List.of(), false),
            new Field("mobilePhone", Type.PHONE, false, 20, List.of(), false),
            new Field("homePhone", Type.PHONE, false, 20, List.of(), false))),
        new Section("address", false, 1, true, List.of(
            required(text("line1", 120)),
            text("line2", 120),
            text("city", 80),
            text("state", 80),
            text("postcode", 12),
            required(text("country", 60)))),
        new Section("bank", false, 1, true, List.of(
            required(text("bankName", 80)),
            required(text("accountName", 120)),
            required(secret("accountNumber", 40)),
            text("branch", 80),
            text("swift", 11))),
        new Section("emergency", true, 3, false, List.of(
            required(text("name", 120)),
            required(text("relationship", 40)),
            new Field("phone", Type.PHONE, true, 20, List.of(), false),
            new Field("altPhone", Type.PHONE, false, 20, List.of(), false))),
        new Section("spouse", false, 1, true, List.of(
            required(text("name", 120)),
            secret("idNumber", 40),
            date("dateOfBirth"),
            text("occupation", 80),
            select("working", YES_NO),
            date("marriageDate"))),
        new Section("children", true, 15, true, List.of(
            required(text("name", 120)),
            required(date("dateOfBirth")),
            select("gender", GENDERS),
            secret("idNumber", 40),
            select("studying", List.of("NONE", "SCHOOL", "HIGHER")),
            select("disabled", YES_NO))),
        new Section("education", true, 10, false, List.of(
            required(select("level", List.of("SECONDARY", "CERTIFICATE", "DIPLOMA", "BACHELOR", "MASTER", "DOCTORATE", "PROFESSIONAL", "OTHER"))),
            required(text("institution", 120)),
            text("field", 120),
            new Field("yearCompleted", Type.YEAR, false, 4, List.of(), false))),
        new Section("national_service", false, 1, true, List.of(
            required(select("status", List.of("NOT_APPLICABLE", "PRE_ENLISTMENT", "SERVING", "COMPLETED", "EXEMPTED"))),
            date("enlistedOn"),
            date("completedOn"),
            text("vocation", 80),
            text("rank", 40))));

    private final SecretBox secrets;

    ProfileSections(SecretBox secrets) {
        this.secrets = secrets;
    }

    List<Section> specs() {
        return SECTIONS;
    }

    Optional<Section> find(String key) {
        return SECTIONS.stream().filter(s -> s.key().equals(key)).findFirst();
    }

    Section require(String key) {
        return find(key).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "UNKNOWN_SECTION", "There's no profile section called " + key + "."));
    }

    // ---------- checking what was typed ----------

    /**
     * Keeps only the section's fields, trimmed, with empty values dropped, and checks each. Returns an empty map when
     * nothing is filled in (the section is cleared). List sections return {@code {"items": [...]}}.
     */
    Map<String, Object> clean(Section section, Object input) {
        Map<?, ?> in = input instanceof Map<?, ?> m ? m : Map.of();
        if (!section.list()) return cleanOne(section, in, null);
        Object raw = in.get(ITEMS);
        List<?> items = raw instanceof List<?> l ? l : List.of();
        if (items.size() > section.maxItems()) {
            throw bad("TOO_MANY_ITEMS", "Add up to " + section.maxItems() + " here.");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            Map<?, ?> item = items.get(i) instanceof Map<?, ?> m ? m : Map.of();
            Map<String, Object> cleaned = cleanOne(section, item, i + 1);
            if (!cleaned.isEmpty()) out.add(cleaned);
        }
        if (out.isEmpty()) return Map.of();
        Map<String, Object> wrapped = new LinkedHashMap<>();
        wrapped.put(ITEMS, out);
        return wrapped;
    }

    private Map<String, Object> cleanOne(Section section, Map<?, ?> in, Integer itemNo) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Field f : section.fields()) {
            Object raw = in.get(f.key());
            String v = raw == null ? null : String.valueOf(raw).trim();
            if (v == null || v.isEmpty()) continue;
            out.put(f.key(), check(f, v, itemNo));
        }
        if (out.isEmpty()) return out;
        for (Field f : section.fields()) {
            if (f.required() && !out.containsKey(f.key())) {
                throw bad("FIELD_REQUIRED", where(itemNo) + "Fill in " + label(f.key()) + ".", f.key());
            }
        }
        return out;
    }

    private static String check(Field f, String v, Integer itemNo) {
        String at = where(itemNo);
        if (v.length() > f.max()) throw bad("FIELD_TOO_LONG", at + label(f.key()) + " can be up to " + f.max() + " characters.", f.key());
        switch (f.type()) {
            case EMAIL -> {
                if (!EMAIL.matcher(v).matches()) throw bad("BAD_EMAIL", at + "That email address doesn't look right.", f.key());
            }
            case PHONE -> {
                if (!PHONE.matcher(v).matches()) throw bad("BAD_PHONE", at + "Use digits, spaces, brackets or dashes for " + label(f.key()) + ", with an optional +.", f.key());
            }
            case YEAR -> {
                if (!YEAR.matcher(v).matches() || Integer.parseInt(v) > LocalDate.now().getYear() + 5) {
                    throw bad("BAD_YEAR", at + "Give " + label(f.key()) + " as a year, like 2019.", f.key());
                }
            }
            case DATE -> {
                LocalDate d;
                try {
                    d = LocalDate.parse(v);
                } catch (DateTimeParseException e) {
                    throw bad("BAD_DATE", at + "Give " + label(f.key()) + " as a date (YYYY-MM-DD).", f.key());
                }
                if (d.getYear() < 1900 || d.getYear() > 2100) throw bad("BAD_DATE", at + label(f.key()) + " is out of range.", f.key());
                if (f.key().equals("dateOfBirth") && d.isAfter(LocalDate.now())) {
                    throw bad("BAD_DATE", at + "The date of birth can't be in the future.", f.key());
                }
            }
            case SELECT -> {
                if (!f.options().contains(v)) throw bad("BAD_OPTION", at + "Choose one of the options for " + label(f.key()) + ".", f.key());
            }
            case TEXT -> {
                if (v.chars().anyMatch(c -> c < 0x20)) throw bad("BAD_TEXT", at + label(f.key()) + " can't contain control characters.", f.key());
            }
        }
        return v;
    }

    // ---------- sensitive values ----------

    /** Encrypts the sensitive values before they're stored. */
    Map<String, Object> seal(Section section, Map<String, Object> clean) {
        return transform(section, clean, v -> {
            if (v.startsWith(SEALED)) return v;
            if (!secrets.ready()) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SECRETS_NOT_CONFIGURED",
                    "The server can't store ID or bank numbers yet: set HRMS_SECRET_KEY first.");
            }
            return SEALED + secrets.seal(v);
        });
    }

    /** Decrypts the sensitive values for someone allowed to see them. */
    Map<String, Object> open(Section section, Map<String, Object> stored) {
        return transform(section, stored, v -> {
            if (!v.startsWith(SEALED)) return v;
            String plain = secrets.open(v.substring(SEALED.length()));
            return plain == null ? "(can't be read)" : plain;
        });
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> transform(Section section, Map<String, Object> data, java.util.function.UnaryOperator<String> fn) {
        if (data == null || data.isEmpty()) return data == null ? Map.of() : data;
        if (section.list()) {
            Object raw = data.get(ITEMS);
            List<Map<String, Object>> items = raw instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put(ITEMS, items.stream().map(i -> transformOne(section, i, fn)).toList());
            return out;
        }
        return transformOne(section, data, fn);
    }

    private static Map<String, Object> transformOne(Section section, Map<String, Object> item, java.util.function.UnaryOperator<String> fn) {
        Map<String, Object> out = new LinkedHashMap<>(item);
        for (Field f : section.fields()) {
            if (f.sensitive() && out.get(f.key()) instanceof String s && !s.isEmpty()) out.put(f.key(), fn.apply(s));
        }
        return out;
    }

    /** The fields whose values differ (for list sections: "items" when the list differs). Both sides opened. */
    List<String> changedFields(Section section, Map<String, Object> before, Map<String, Object> after) {
        Map<String, Object> b = before == null ? Map.of() : before;
        Map<String, Object> a = after == null ? Map.of() : after;
        if (section.list()) return Objects.equals(b.get(ITEMS), a.get(ITEMS)) ? List.of() : List.of(ITEMS);
        return section.fields().stream().map(Field::key).filter(k -> !Objects.equals(b.get(k), a.get(k))).toList();
    }

    // ---------- helpers ----------

    private static Field text(String key, int max) {
        return new Field(key, Type.TEXT, false, max, List.of(), false);
    }

    private static Field secret(String key, int max) {
        return new Field(key, Type.TEXT, false, max, List.of(), true);
    }

    private static Field date(String key) {
        return new Field(key, Type.DATE, false, 10, List.of(), false);
    }

    private static Field select(String key, List<String> options) {
        return new Field(key, Type.SELECT, false, 40, options, false);
    }

    private static Field required(Field f) {
        return new Field(f.key(), f.type(), true, f.max(), f.options(), f.sensitive());
    }

    private static String where(Integer itemNo) {
        return itemNo == null ? "" : "Entry " + itemNo + ": ";
    }

    /** "accountNumber" → "account number". */
    static String label(String key) {
        return key.replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase();
    }

    private static ApiException bad(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    private static ApiException bad(String code, String message, String field) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message).with("field", field);
    }
}
