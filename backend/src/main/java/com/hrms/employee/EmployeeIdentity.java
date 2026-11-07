package com.hrms.employee;

import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The identity and statutory numbers printed on payslips (IC, tax, EPF and SOCSO numbers), read from the person's
 * personal details and decrypted. Only for documents the person or HR will see, such as their own payslip.
 */
@Component
public class EmployeeIdentity {

    public record Numbers(String idNumber, String taxNumber, String epfNumber, String socsoNumber) {
        public static final Numbers NONE = new Numbers(null, null, null, null);
    }

    private final ProfileService profiles;
    private final ProfileSections sections;

    EmployeeIdentity(ProfileService profiles, ProfileSections sections) {
        this.profiles = profiles;
        this.sections = sections;
    }

    public Numbers numbers(UUID employeeId) {
        var personal = sections.require("personal");
        Map<String, Object> data = sections.open(personal, profiles.storedSection(employeeId, "personal"));
        return new Numbers(text(data.get("idNumber")), text(data.get("taxNumber")), text(data.get("epfNumber")), text(data.get("socsoNumber")));
    }

    private static String text(Object v) {
        return v == null || String.valueOf(v).isBlank() ? null : String.valueOf(v);
    }
}
