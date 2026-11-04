# Demo accounts (local development only)

These accounts are created by the local seed data (`hrms.seed.enabled=true`, the `local` profile). They exist only in your local database. Never reuse this password anywhere real.

- **Sign in:** http://localhost:5173/login
- **Password for all accounts below (except Mei Chen):** `hrms-demo-123` (change it with `HRMS_SEED_PASSWORD` before the first seed)
- You can sign in with the email or the mobile number.

## Demo Sdn Bhd (company code `DEMO`)

| Role | Email | Mobile | What to test |
|---|---|---|---|
| Super admin | `aisyah@demo.hrms.local` | +60120000001 | Everything, including Roles and Company settings. Approves step 2 of annual leave. Also belongs to Demo Pte Ltd, so signing in shows the company picker. |
| HR admin + Employee | `priya@demo.hrms.local` | +60120000004 | Users, Leave settings, Reports (whole company), HR approval steps, record leave for someone, cancel as HR. Can't open Roles or Company. |
| Manager | `daniel@demo.hrms.local` | +60120000003 | Approves for his team (Wei Ling, Arjun, Mei). Sees his team on People, Who's off and Reports. |
| Manager | `arjun@demo.hrms.local` | +60120000005 | Seeded as an Employee; promoted to Manager while testing roles. Reports to Daniel. |
| Employee | `weiling@demo.hrms.local` | +60120000002 | Self-service: apply for leave, see who it's waiting for, notifications, edit a request that was sent back. Sees only herself on People. |
| Claims clerk (custom role) | `mei@demo.hrms.local` | — | Created during testing with a random temporary password. To use her: sign in as Aisyah or Priya → **Users → Mei Chen → Reset password**; the new password is shown once. |

## Demo Pte Ltd (company code `DEMO-SG`)

| Role | Email | Mobile | Note |
|---|---|---|---|
| Manager | `aisyah@demo.hrms.local` | +6580000001 | Same email and password as above; choose "Demo Pte Ltd" in the company picker. The Claims module is off in this company. |

## Tips

- If the browser autofills one of your own saved accounts on the login page, clear it and type the demo email.
- To start over with fresh demo data, recreate the local database (`drop database hrms; create database hrms owner hrms_app;`) and restart the API; the seed runs again on startup.
