import { useEffect, useId, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Logo } from './Logo';
import { ButtonLink } from './Button';
import { Icon } from './Icon';
import { ThemeToggle } from './ThemeToggle';
import { LanguageSwitcher } from './LanguageSwitcher';

export type NavItem = { href: string; label: string };

type Props = {
  /** In-page or site links shown in the middle. Leave empty on pages that only need the actions. */
  links?: NavItem[];
};

/**
 * Site header shared by public pages: logo, links, and the two entry points (Log in, Get started).
 * Sticky; below 760px the links and actions fold into a menu.
 */
export function TopNav({ links = [] }: Props) {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  const [scrolled, setScrolled] = useState(false);
  const menuId = useId();

  // Show the bottom border only once content scrolls under the header.
  useEffect(() => {
    const onScroll = () => setScrolled(window.scrollY > 4);
    onScroll();
    window.addEventListener('scroll', onScroll, { passive: true });
    return () => window.removeEventListener('scroll', onScroll);
  }, []);

  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && setOpen(false);
    const onResize = () => window.innerWidth >= 760 && setOpen(false);
    window.addEventListener('keydown', onKey);
    window.addEventListener('resize', onResize);
    return () => {
      window.removeEventListener('keydown', onKey);
      window.removeEventListener('resize', onResize);
    };
  }, [open]);

  const close = () => setOpen(false);

  return (
    <header className={['ui-topnav', open && 'is-open', scrolled && 'is-scrolled'].filter(Boolean).join(' ')}>
      <div className="ui-container ui-topnav__bar">
        <Logo />

        {links.length > 0 && (
          <nav className="ui-topnav__links" aria-label={t('site.navLabel')}>
            {links.map((l) => (
              <a key={l.href} href={l.href}>{l.label}</a>
            ))}
          </nav>
        )}

        <div className="ui-topnav__actions">
          <LanguageSwitcher />
          <ThemeToggle />
          <ButtonLink to="/login" variant="ghost" size="sm">{t('site.login')}</ButtonLink>
          <ButtonLink to="/get-started" variant="primary" size="sm">{t('site.getStarted')}</ButtonLink>
        </div>

        <div className="ui-topnav__theme-mobile">
          <LanguageSwitcher />
          <ThemeToggle />
        </div>

        <button
          type="button"
          className="ui-topnav__toggle"
          aria-expanded={open}
          aria-controls={menuId}
          aria-label={open ? t('site.closeMenu') : t('site.openMenu')}
          onClick={() => setOpen((v) => !v)}
        >
          <Icon name={open ? 'close' : 'menu'} size={20} />
        </button>
      </div>

      <div id={menuId} className="ui-topnav__sheet" hidden={!open}>
        <div className="ui-container">
          {links.length > 0 && (
            <nav aria-label={t('site.navLabel')}>
              {links.map((l) => (
                <a key={l.href} href={l.href} onClick={close}>{l.label}</a>
              ))}
            </nav>
          )}
          <div className="ui-topnav__sheet-actions">
            <ButtonLink to="/login" variant="secondary" size="md" onClick={close}>{t('site.login')}</ButtonLink>
            <ButtonLink to="/get-started" variant="primary" size="md" onClick={close}>{t('site.getStarted')}</ButtonLink>
          </div>
        </div>
      </div>
    </header>
  );
}
