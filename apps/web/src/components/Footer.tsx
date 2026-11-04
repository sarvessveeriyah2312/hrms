import { Link } from 'react-router';
import { useTranslation } from 'react-i18next';
import { Logo } from './Logo';
import { PRODUCT_NAME } from '../brand';

const YEAR = new Date().getFullYear();

export function Footer() {
  const { t } = useTranslation();
  return (
    <footer className="ui-footer">
      <div className="ui-container ui-footer__inner">
        <Logo />
        <nav className="ui-footer__links" aria-label={t('site.footerLabel')}>
          <Link to="/login">{t('site.login')}</Link>
          <Link to="/get-started">{t('site.getStarted')}</Link>
          <a href="/#privacy">{t('site.privacy')}</a>
        </nav>
        <p className="ui-footer__legal">© {YEAR} {PRODUCT_NAME}</p>
      </div>
    </footer>
  );
}
