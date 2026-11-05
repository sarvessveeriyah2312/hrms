import { useEffect, useState } from 'react';
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import type { Me } from '@hrms/api-client';
import { api, signOut } from '../lib/api';
import { useAppTitle } from '../lib/title';
import { can, canAny, hasModule, useAccess } from '../lib/access';
import { MODULE_META } from '../lib/modules';
import { toggleTheme, useTheme } from '../lib/theme';
import { Icon, type IconName } from '../components/Icon';
import { ThemeToggle } from '../components/ThemeToggle';
import { LanguageSwitcher } from '../components/LanguageSwitcher';
import { NotificationBell } from '../components/app/NotificationBell';
import { SearchPalette, type SearchPage } from '../components/app/SearchPalette';
import { Avatar, Menu, type MenuItem } from '../components/app/kit';
import { useInbox } from '../features/approvals/useInbox';
import type { en } from '../i18n/en';
import './app.css';

type NavItem = { to: string; key: keyof typeof en.nav; icon: IconName; end?: boolean; show: (u: Me | null) => boolean; count?: number };

/**
 * Menus follow the user's permissions and the company's modules. The API enforces the same rules.
 * `end`: only the page itself counts as active, so Leave isn't highlighted on Leave settings (/app/leave/settings).
 */
const NAV: NavItem[] = [
  { to: '/app', key: 'dashboard', icon: 'home', end: true, show: () => true },
  { to: '/app/people', key: 'people', icon: 'users', show: (u) => can(u, 'employee:view') },
  { to: '/app/attendance', key: 'attendance', icon: 'clock', end: true, show: (u) => hasModule(u, 'attendance') && canAny(u, ['attendance:clock', 'attendance:view']) },
  { to: '/app/leave', key: 'leave', icon: 'calendar', end: true, show: (u) => hasModule(u, 'leave') && canAny(u, ['leave:apply', 'leave:view']) },
  { to: '/app/claims', key: 'claims', icon: 'receipt', end: true, show: (u) => hasModule(u, 'claims') && canAny(u, ['claims:submit', 'claims:view']) },
  { to: '/app/approvals', key: 'approvals', icon: 'inbox', show: (u) => canAny(u, ['attendance:approve', 'leave:approve', 'claims:approve']) },
  { to: '/app/reports', key: 'reports', icon: 'chart', show: (u) => hasModule(u, 'reports') && canAny(u, ['reports:leave', 'reports:claims']) },
];

const ADMIN_NAV: NavItem[] = [
  { to: '/app/admin/users', key: 'users', icon: 'users', show: (u) => can(u, 'admin:users') },
  { to: '/app/admin/roles', key: 'roles', icon: 'shield', show: (u) => can(u, 'admin:roles') },
  { to: '/app/admin/company', key: 'company', icon: 'building', show: (u) => can(u, 'admin:company') },
  { to: '/app/attendance/sites', key: 'sites', icon: 'pin', show: (u) => hasModule(u, 'attendance') && can(u, 'attendance:manage') },
  { to: '/app/leave/settings', key: 'leaveSettings', icon: 'calendar', show: (u) => hasModule(u, 'leave') && can(u, 'leave:manage') },
  { to: '/app/claims/settings', key: 'claimSettings', icon: 'receipt', show: (u) => hasModule(u, 'claims') && can(u, 'claims:manage') },
  { to: '/app/admin/workflows', key: 'workflows', icon: 'switch', show: (u) => can(u, 'workflow:manage') },
];

const LICENSABLE = ['attendance', 'leave', 'claims', 'reports'];

// ⌘K on Apple devices, Ctrl+K elsewhere.
const IS_MAC = typeof navigator !== 'undefined' && /Mac|iPhone|iPad/.test(navigator.platform || navigator.userAgent);
const MOD_KEY = IS_MAC ? 'Meta' : 'Control';
const MOD_LABEL = IS_MAC ? '⌘' : 'Ctrl ';

// Collapsed sidebar (icons only) is remembered on this device. Storage can be blocked; then it just isn't saved.
const COLLAPSE_KEY = 'hrms-side-collapsed';
function readCollapsed() {
  try {
    return localStorage.getItem(COLLAPSE_KEY) === '1';
  } catch {
    return false;
  }
}
function saveCollapsed(on: boolean) {
  try {
    localStorage.setItem(COLLAPSE_KEY, on ? '1' : '0');
  } catch {
    // not saved; the choice still holds for this visit
  }
}

function NavItems({ items, user, collapsed, onNavigate }: { items: NavItem[]; user: Me | null; collapsed: boolean; onNavigate: () => void }) {
  const { t } = useTranslation();
  return (
    <ul className="side-nav">
      {items.filter((i) => i.show(user)).map((item) => (
        <li key={item.to}>
          <NavLink to={item.to} end={item.end ?? false} onClick={onNavigate} title={collapsed ? t(`nav.${item.key}`) : undefined}
            className={({ isActive }) => (isActive ? 'side-link is-active' : 'side-link')}>
            <Icon name={item.icon} size={17} />
            <span className="side-link__label">{t(`nav.${item.key}`)}</span>
            {item.count ? <span className="side-link__count" aria-label={t('shell.waiting', { count: item.count })}>{item.count}</span> : null}
          </NavLink>
        </li>
      ))}
    </ul>
  );
}

/** Sidebar + framed main panel, as in the design references. On narrow screens the sidebar becomes a drawer. */
export function AppShell() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const location = useLocation();
  const queryClient = useQueryClient();
  const theme = useTheme();
  const { user } = useAccess();
  // Shares the bell's query (same key), so this adds no extra requests.
  const notifications = useQuery({ queryKey: ['notifications'], queryFn: () => api.notifications.list(30), refetchInterval: 30_000 });
  useAppTitle({ company: user?.companyName, unread: notifications.data?.unread ?? 0 });
  const [drawer, setDrawer] = useState(false);
  const [collapsed, setCollapsed] = useState(readCollapsed);
  const toggleCollapsed = () => setCollapsed((c) => {
    saveCollapsed(!c);
    return !c;
  });
  const [searching, setSearching] = useState(false);

  const approver = canAny(user, ['attendance:approve', 'leave:approve', 'claims:approve']);
  const inbox = useInbox(approver);
  const nav = NAV.map((i) => (i.key === 'approvals' ? { ...i, count: inbox.data?.count } : i));
  const all = [...NAV, ...ADMIN_NAV];
  const current = all
    .filter((i) => (i.end ? location.pathname === i.to : location.pathname.startsWith(i.to)))
    .sort((a, b) => b.to.length - a.to.length)[0];
  const showAdmin = ADMIN_NAV.some((i) => i.show(user));
  const modulesOn = LICENSABLE.filter((m) => user?.modules.includes(m));

  // ⌘K / Ctrl+K focuses search, as the reference shows.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault();
        setSearching(true);
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);

  const leave = async (to: string, state?: unknown) => {
    await signOut();
    queryClient.clear();
    navigate(to, { replace: true, state });
  };
  const switchCompany = () => leave('/login', { username: user?.email ?? '', switchCompany: true });

  const newItems: MenuItem[] = [
    ...(can(user, 'admin:users') ? [{ label: t('shell.newUser'), icon: 'users' as const, to: '/app/admin/users?new=1' }] : []),
    ...(can(user, 'admin:roles') ? [{ label: t('shell.newRole'), icon: 'shield' as const, to: '/app/admin/roles?new=1' }] : []),
    ...(hasModule(user, 'leave') && can(user, 'leave:apply') ? [{ label: t('shell.newLeave'), icon: 'calendar' as const, to: '/app/leave?apply=1' }] : []),
    ...(hasModule(user, 'claims') && can(user, 'claims:submit') ? [{ label: t('shell.newClaim'), icon: 'receipt' as const, to: '/app/claims?new=1' }] : []),
  ];

  const closeDrawer = () => setDrawer(false);

  // What search offers before you type, and matches by name: the pages you can open and the "New" actions.
  const searchPages: SearchPage[] = [
    ...[...NAV, ...ADMIN_NAV].filter((i) => i.show(user)).map((i) => ({ label: t(`nav.${i.key}`), icon: i.icon, to: i.to })),
    ...(hasModule(user, 'leave') && canAny(user, ['leave:apply', 'leave:view'])
      ? [{ label: `${t('nav.leave')} · ${t('leave.tabs.calendar')}`, icon: 'calendar' as const, to: '/app/leave?tab=calendar' }] : []),
    ...(hasModule(user, 'leave') && can(user, 'leave:apply')
      ? [{ label: `${t('nav.leave')} · ${t('leave.tabs.planner')}`, icon: 'calendar' as const, to: '/app/leave?tab=planner' }] : []),
    ...(hasModule(user, 'leave') && can(user, 'leave:manage') ? [
      { label: `${t('nav.leaveSettings')} · ${t('ls.tabs.holidays')}`, icon: 'star' as const, to: '/app/leave/settings?tab=holidays' },
      { label: `${t('nav.leaveSettings')} · ${t('ls.tabs.entitlements')}`, icon: 'users' as const, to: '/app/leave/settings?tab=entitlements' },
    ] : []),
    ...(hasModule(user, 'claims') && can(user, 'claims:manage')
      ? [{ label: `${t('nav.claims')} · ${t('claims.tabs.pay')}`, icon: 'receipt' as const, to: '/app/claims?tab=pay' }] : []),
    ...(hasModule(user, 'reports') && hasModule(user, 'claims') && can(user, 'reports:claims')
      ? [{ label: `${t('nav.reports')} · ${t('nav.claims')}`, icon: 'chart' as const, to: '/app/reports?report=claims' }] : []),
    ...(can(user, 'admin:roles') ? [{ label: `${t('nav.roles')} · ${t('roles.compare')}`, icon: 'shield' as const, to: '/app/admin/roles?tab=compare' }] : []),
    ...(can(user, 'admin:company') ? [
      { label: `${t('nav.company')} · ${t('users.departments')}`, icon: 'building' as const, to: '/app/admin/company?tab=departments' },
      { label: `${t('nav.company')} · ${t('shell.modules')}`, icon: 'sliders' as const, to: '/app/admin/company?tab=modules' },
    ] : []),
    ...newItems.filter((i): i is Exclude<MenuItem, 'divider'> => i !== 'divider' && !!i.to)
      .map((i) => ({ label: `${t('shell.new')}: ${i.label}`, icon: i.icon ?? 'plus', to: i.to!, keywords: t('shell.new'), action: true })),
  ];

  return (
    <div className={['app', drawer && 'is-drawer-open', collapsed && 'is-collapsed'].filter(Boolean).join(' ')}>
      <aside className="side" aria-label={t('shell.main')}>
        <div className="side-top">
          <Menu
            className="org-menu"
            label={t('shell.companyMenu', { company: user?.companyName ?? t('nav.company') })}
            trigger={
              <>
                <span className="org-mark" aria-hidden="true">{(user?.companyName ?? 'H')[0]}</span>
                <span className="org-name">{user?.companyName}</span>
                <Icon name="chevronDown" size={16} />
              </>
            }
            items={[
              { label: `Copy company code (${user?.companyCode ?? ''})`, icon: 'building', onSelect: () => void navigator.clipboard?.writeText(user?.companyCode ?? '') },
              { label: t('auth.switchCompany'), icon: 'switch', onSelect: switchCompany },
            ]}
          />
          <button type="button" className="side-close" onClick={closeDrawer} aria-label={t('site.closeMenu')}><Icon name="close" size={18} /></button>
        </div>

        <div className="side-actions">
          {newItems.length > 0 ? (
            <Menu className="new-menu" label={t('shell.new')} trigger={<><Icon name="plus" size={16} /><span>{t('shell.new')}</span></>} items={newItems} />
          ) : <span />}
          <button type="button" className="icon-pill" aria-label={t('search.title')} onClick={() => { closeDrawer(); setSearching(true); }}>
            <Icon name="search" size={16} />
          </button>
        </div>

        <nav aria-label={t('shell.modules')}>
          <NavItems items={nav} user={user} collapsed={collapsed} onNavigate={closeDrawer} />
        </nav>
        {showAdmin && (
          <nav aria-labelledby="admin-heading">
            <p id="admin-heading" className="side-label">{t('nav.admin')}</p>
            <NavItems items={ADMIN_NAV} user={user} collapsed={collapsed} onNavigate={closeDrawer} />
          </nav>
        )}

        <div className="side-bottom">
          <div className="side-card">
            <div className="side-card__head">
              <Icon name="sliders" size={15} />
              <span>{t('shell.modules')}</span>
              <span className="side-card__badge">{user?.companyCode}</span>
            </div>
            <p className="side-card__value"><strong>{modulesOn.length}</strong> {t('shell.modulesOf', { total: LICENSABLE.length })}</p>
            <div className="segments" aria-hidden="true">
              {LICENSABLE.map((m) => (
                <span key={m} className={modulesOn.includes(m) ? 'is-on' : undefined} title={MODULE_META[m].label} />
              ))}
            </div>
            <div className="side-card__foot">
              <span>{LICENSABLE.filter((m) => !modulesOn.includes(m)).map((m) => MODULE_META[m].label).join(', ') || t('shell.allOn')}</span>
              {can(user, 'admin:company') && <NavLink to="/app/admin/company" onClick={closeDrawer}>{t('common.manage')}</NavLink>}
            </div>
          </div>

          <div className="side-user">
            <Avatar name={user?.name ?? user?.email} size={34} />
            <span className="side-user__text">
              <strong>{user?.name ?? t('auth.signedIn')}</strong>
              <small>{user?.email}</small>
            </span>
            <Menu
              align="up"
              label={t('shell.accountMenu')}
              trigger={<Icon name="gear" size={17} />}
              items={[
                { label: theme === 'dark' ? t('shell.lightMode') : t('shell.darkMode'), icon: theme === 'dark' ? 'sun' : 'moon', onSelect: toggleTheme },
                { label: t('password.changeTitle'), icon: 'lock', to: '/app/account/password' },
                { label: t('auth.switchCompany'), icon: 'switch', onSelect: switchCompany },
                'divider',
                { label: t('auth.signOut'), icon: 'logout', onSelect: () => void leave('/login') },
              ]}
            />
          </div>
        </div>
      </aside>
      <button type="button" className="drawer-scrim" aria-label={t('site.closeMenu')} tabIndex={-1} onClick={closeDrawer} />

      <div className="frame">
        <header className="bar">
          <button type="button" className="icon-pill bar-menu" aria-label={t('site.openMenu')} onClick={() => setDrawer(true)}>
            <Icon name="menu" size={18} />
          </button>
          <button type="button" className="bar-collapse" onClick={toggleCollapsed} aria-pressed={collapsed}
            aria-label={collapsed ? t('shell.expandSide') : t('shell.collapseSide')} title={collapsed ? t('shell.expandSide') : t('shell.collapseSide')}>
            <Icon name="sidebar" size={18} />
          </button>
          <span className="crumb">
            <Icon name={current?.icon ?? 'home'} size={16} />
            {current ? t(`nav.${current.key}`) : t('nav.dashboard')}
          </span>
          <button type="button" className="bar-search" onClick={() => setSearching(true)} aria-haspopup="dialog" aria-keyshortcuts={`${MOD_KEY}+K`}>
            <Icon name="search" size={15} />
            <span className="bar-search__text">{t('search.placeholder')}</span>
            <kbd>{MOD_LABEL}K</kbd>
          </button>
          <div className="bar-end">
            <NotificationBell />
            <LanguageSwitcher />
            <ThemeToggle />
          </div>
        </header>
        <main className="canvas" id="main">
          <Outlet />
        </main>
        {searching && <SearchPalette pages={searchPages} onClose={() => setSearching(false)} />}
      </div>
    </div>
  );
}
