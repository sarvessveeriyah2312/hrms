package com.hrms.employee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hrms.shared.mail.SecretBox;
import com.hrms.shared.web.ApiException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** What a profile section accepts, and how sensitive values are sealed and opened. */
class ProfileSectionsTests {

    private static final String KEY = "bG9jYWwtb25seS1kby1ub3QtdXNlLWluLXByb2R1Y3Q=";
    private final ProfileSections sections = new ProfileSections(box(KEY));

    private static SecretBox box(String key) {
        try {
            var c = SecretBox.class.getDeclaredConstructor(String.class);
            c.setAccessible(true);
            return c.newInstance(key);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private void refused(String section, Object input, String code) {
        assertThatThrownBy(() -> sections.clean(sections.require(section), input))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(code));
    }

    @Test
    void keepsOnlyKnownFieldsTrimmed() {
        Map<String, Object> out = sections.clean(sections.require("contact"),
            Map.of("mobilePhone", " +60 12-345 6789 ", "personalEmail", "", "hacker", "x"));
        assertThat(out).containsExactly(Map.entry("mobilePhone", "+60 12-345 6789"));
    }

    @Test
    void anEmptySectionClearsIt() {
        assertThat(sections.clean(sections.require("bank"), Map.of("bankName", " "))).isEmpty();
    }

    @Test
    void requiredFieldsOnlyOnceSomethingIsFilledIn() {
        refused("bank", Map.of("bankName", "Maybank"), "FIELD_REQUIRED");
    }

    @Test
    void checksTypes() {
        refused("contact", Map.of("personalEmail", "not-an-email"), "BAD_EMAIL");
        refused("contact", Map.of("mobilePhone", "call me"), "BAD_PHONE");
        refused("personal", Map.of("gender", "OTHER"), "BAD_OPTION");
        refused("personal", Map.of("dateOfBirth", "2999-01-01"), "BAD_DATE");
        refused("personal", Map.of("dateOfBirth", "01/02/1990"), "BAD_DATE");
        refused("education", Map.of("items", List.of(Map.of("level", "BACHELOR", "institution", "UM", "yearCompleted", "19"))), "BAD_YEAR");
    }

    @Test
    void listSectionsDropEmptyItemsAndLimitTheCount() {
        Map<String, Object> out = sections.clean(sections.require("emergency"), Map.of("items", List.of(
            Map.of("name", "Ali", "relationship", "Brother", "phone", "0123456789"), Map.of("name", ""))));
        assertThat((List<?>) out.get("items")).hasSize(1);
        Map<String, String> one = Map.of("name", "Ali", "relationship", "Brother", "phone", "0123456789");
        refused("emergency", Map.of("items", List.of(one, one, one, one)), "TOO_MANY_ITEMS");
    }

    @Test
    void sealsSensitiveValuesAndOpensThem() {
        var bank = sections.require("bank");
        Map<String, Object> clean = sections.clean(bank, Map.of("bankName", "Maybank", "accountName", "Wei Ling", "accountNumber", "1234567890"));
        Map<String, Object> sealed = sections.seal(bank, clean);
        assertThat((String) sealed.get("accountNumber")).startsWith("enc:").doesNotContain("1234567890");
        assertThat(sealed.get("bankName")).isEqualTo("Maybank");
        assertThat(sections.open(bank, sealed)).isEqualTo(clean);
        // Sealing twice doesn't seal again.
        assertThat(sections.seal(bank, sealed)).isEqualTo(sealed);
    }

    @Test
    void refusesSensitiveValuesWithoutAKey() {
        ProfileSections noKey = new ProfileSections(box(""));
        var bank = noKey.require("bank");
        Map<String, Object> clean = noKey.clean(bank, Map.of("bankName", "Maybank", "accountName", "A", "accountNumber", "1"));
        assertThatThrownBy(() -> noKey.seal(bank, clean))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("SECRETS_NOT_CONFIGURED"));
    }

    @Test
    void listsChangedFields() {
        var contact = sections.require("contact");
        assertThat(sections.changedFields(contact, Map.of("mobilePhone", "1"), Map.of("mobilePhone", "2", "homePhone", "3")))
            .containsExactly("mobilePhone", "homePhone");
        var children = sections.require("children");
        assertThat(sections.changedFields(children, Map.of(), Map.of("items", List.of(Map.of("name", "A"))))).containsExactly("items");
    }
}
