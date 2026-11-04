import { useEffect, useRef, useState } from 'react';
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
import { Avatar, Menu, type MenuItem } from '../components/app/kit';
import { useInbox } from '../features/approvals/useInbox';
import type { en } from '../i18n/en';
import './app.css';

type NavItem = { to: string; key: keyof typeof en.nav; icon: IconName; end?: boolean; show: (u: Me | null) => boolean; count?: number };

/** Menus follow the user's permissions and the company's modules. The API enforces the same rules. */
const NAV: NavItem[] = [
  { to: '/app', key: 'dashboard', icon: 'home', end: true, show: () => true },
  { to: '/app/people', key: 'people', icon: 'users', show: (u) => can(u, 'employee:view') },
  { to: '/app/attendance', key: 'attendance', icon: 'clock', show: (u) => hasModule(u, 'attendance') && canAny(u, ['attendance:clock', 'attendance:view']) },
  { to: '/app/leave', key: 'leave', icon: 'calendar', show: (u) => hasModule(u, 'leave') && canAny(u, ['leave:apply', 'leave:view']) },
  { to: '/app/claims', key: 'claims', icon: 'receipt', show: (u) => hasModule(u, 'claims') && canAny(u, ['claims:submit', 'claims:view']) },
  { to: '/app/approvals', key: 'approvals', icon: 'inbox', show: (u) => canAny(u, ['attendance:approve', 'leave:approve', 'claims:approve']) },
  { to: '/app/reports', key: 'reports', icon: 'chart', show: (u) => hasModule(u, 'reports') && can(u, 'reports:view') },
];

const ADMIN_NAV: NavItem[] = [
  { to: '/app/admin/users', key: 'users', icon: 'users', show: (u) => can(u, 'admin:users') },
  { to: '/app/admin/roles', key: 'roles', icon: 'shield', show: (u) => can(u, 'admin:roles') },
  { to: '/app/admin/company', key: 'company', icon: 'building', show: (u) => can(u, 'admin:company') },
  { to: '/app/leave/settings', key: 'leaveSettings', icon: 'calendar', show: (u) => hasModule(u, 'leave') && can(u, 'leave:manage') },
  { to: '/app/admin/workflows', key: 'workflows', icon: 'switch', show: (u) => can(u, 'workflow:manage') },
];

const LICENSABLE = ['attendance', 'leave', 'claims', 'reports'];

function NavItems({ items, user, onNavigate }: { items: NavItem[]; user: Me | null; onNavigate: () => void }) {
  const { t } = useTranslation();
  return (
    <ul className="side-nav">
      {items.filter((i) => i.show(user)).map((item) => (
        <li key={item.to}>
          <NavLink to={item.to} end={item.end ?? false} onClick={onNavigate} className={({ isActive }) => (isActive ? 'side-link is-active' : 'side-link')}>
            <Icon name={item.icon} size={17} />
            <span>{t(`nav.${item.key}`)}</span>
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
  const [query, setQuery] = useState('');
  const searchRef = useRef<HTMLInputElement>(null);

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
        searchRef.current?.focus();
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
    ...(hasModule(user, 'claims') && can(user, 'claims:submit') ? [{ label: t('shell.newClaim'), icon: 'receipt' as const, to: '/app/claims', hint: t('shell.soon') }] : []),
  ];

  const closeDrawer = () => setDrawer(false);

  return (
    <div className={drawer ? 'app is-drawer-open' : 'app'}>
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
          <button type="button" className="icon-pill" aria-label={t('common.search')} onClick={() => searchRef.current?.focus()}>
            <Icon name="search" size={16} />
          </button>
        </div>

        <nav aria-label={t('shell.modules')}>
          <NavItems items={nav} user={user} onNavigate={closeDrawer} />
        </nav>
        {showAdmin && (
          <nav aria-labelledby="admin-heading">
            <p id="admin-heading" className="side-label">{t('nav.admin')}</p>
            <NavItems items={ADMIN_NAV} user={user} onNavigate={closeDrawer} />
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
          <span className="crumb">
            <Icon name={current?.icon ?? 'home'} size={16} />
            {current ? t(`nav.${current.key}`) : t('nav.dashboard')}
          </span>
          <form
            className="bar-search"
            role="search"
            onSubmit={(e) => {
              e.preventDefault();
              if (can(user, 'employee:view')) navigate(`/app/people?q=${encodeURIComponent(query)}`);
            }}
          >
            <Icon name="search" size={15} />
            <input ref={searchRef} type="search" value={query} onChange={(e) => setQuery(e.target.value)} placeholder={t('shell.searchPeople')} aria-label={t('shell.searchPeople')} />
            <kbd>⌘K</kbd>
          </form>
          <div className="bar-end">
            <NotificationBell />
            <LanguageSwitcher />
            <ThemeToggle />
          </div>
        </header>
        <main className="canvas" id="main">
          <Outlet />
        </main>
      </div>
    </div>
  );
}
