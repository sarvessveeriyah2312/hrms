import { createBrowserRouter } from 'react-router';
import { AppShell } from './AppShell';
import { DashboardPage } from '../features/dashboard/DashboardPage';
import { ApprovalsPage } from '../features/approvals/ApprovalsPage';
import { LoginPage } from '../features/auth/LoginPage';
import { LandingPage } from '../features/landing/LandingPage';
import { GetStartedPage } from '../features/landing/GetStartedPage';
import { RequireAuth } from './RequireAuth';
import { RequirePermission } from './RequirePermission';
import { PeoplePage } from '../features/people/PeoplePage';
import { UsersPage } from '../features/admin/UsersPage';
import { RolesPage } from '../features/admin/RolesPage';
import { CompanyPage } from '../features/admin/CompanyPage';
import { WorkflowsPage } from '../features/workflows/WorkflowsPage';
import { LeavePage } from '../features/leave/LeavePage';
import { LeaveSettingsPage } from '../features/leave/LeaveSettingsPage';
import { ReportsPage } from '../features/reports/ReportsPage';
import { ClaimsPage } from '../features/claims/ClaimsPage';
import { ClaimSettingsPage } from '../features/claims/ClaimSettingsPage';
import { ForgotPasswordPage } from '../features/auth/ForgotPasswordPage';
import { SitesPage } from '../features/attendance/SitesPage';
import { OvertimeRulesPage } from '../features/attendance/OvertimeRulesPage';
import { RostersPage } from '../features/attendance/RostersPage';
import { AttendancePage } from '../features/attendance/AttendancePage';
import { ResetPasswordPage } from '../features/auth/ResetPasswordPage';
import { ChangePasswordPage } from '../features/account/ChangePasswordPage';

export const router = createBrowserRouter([
  // Public site
  { path: '/', element: <LandingPage /> },
  { path: '/get-started', element: <GetStartedPage /> },
  { path: '/login', element: <LoginPage /> },
  { path: '/forgot-password', element: <ForgotPasswordPage /> },
  { path: '/reset-password', element: <ResetPasswordPage /> },

  // Signed-in app: RequireAuth restores the session from the refresh cookie or sends you to /login.
  {
    path: '/app',
    element: <RequireAuth />,
    children: [
      {
        element: <AppShell />,
        children: [
          { index: true, element: <DashboardPage /> },
          { path: 'approvals', element: <RequirePermission any={['attendance:approve', 'leave:approve', 'claims:approve']}><ApprovalsPage /></RequirePermission> },
          { path: 'account/password', element: <ChangePasswordPage /> },
          { path: 'attendance/sites', element: <RequirePermission module="attendance" any={['attendance:manage']}><SitesPage /></RequirePermission> },
          { path: 'attendance/rosters', element: <RequirePermission module="attendance" any={['attendance:roster', 'attendance:manage']}><RostersPage /></RequirePermission> },
          { path: 'attendance/overtime-rules', element: <RequirePermission module="attendance" any={['attendance:manage']}><OvertimeRulesPage /></RequirePermission> },
          { path: 'people', element: <RequirePermission any={['employee:view']}><PeoplePage /></RequirePermission> },
          { path: 'attendance', element: <RequirePermission module="attendance" any={['attendance:clock', 'attendance:view', 'attendance:timesheets']}><AttendancePage /></RequirePermission> },
          { path: 'leave', element: <RequirePermission module="leave" any={['leave:apply', 'leave:view']}><LeavePage /></RequirePermission> },
          { path: 'leave/settings', element: <RequirePermission module="leave" any={['leave:manage']}><LeaveSettingsPage /></RequirePermission> },
          { path: 'claims', element: <RequirePermission module="claims" any={['claims:submit', 'claims:view']}><ClaimsPage /></RequirePermission> },
          { path: 'claims/settings', element: <RequirePermission module="claims" any={['claims:manage']}><ClaimSettingsPage /></RequirePermission> },
          { path: 'reports', element: <RequirePermission module="reports" any={['reports:leave', 'reports:claims', 'reports:attendance']}><ReportsPage /></RequirePermission> },
          { path: 'admin/users', element: <RequirePermission any={['admin:users']}><UsersPage /></RequirePermission> },
          { path: 'admin/roles', element: <RequirePermission any={['admin:roles']}><RolesPage /></RequirePermission> },
          { path: 'admin/workflows', element: <RequirePermission any={['workflow:manage']}><WorkflowsPage /></RequirePermission> },
          { path: 'admin/company', element: <RequirePermission any={['admin:company']}><CompanyPage /></RequirePermission> },
        ],
      },
    ],
  },
]);
