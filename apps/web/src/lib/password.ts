/** Matches the server's rule (PasswordPolicy): at least 10 characters. */
export const MIN_PASSWORD = 10;

/** Long enough, and typed the same twice. The server checks the rest (common passwords, email in it). */
export function passwordReady(value: string, confirm: string) {
  return value.length >= MIN_PASSWORD && value === confirm;
}
