import { useTranslation } from 'react-i18next';
import { TopNav } from '../../components/TopNav';
import { Footer } from '../../components/Footer';
import { ButtonLink } from '../../components/Button';
import { usePublicTitle } from '../../lib/title';
import './landing.css';

/** Placeholder until company sign-up is built (identity module). Keeps the "Get started" links working. */
export function GetStartedPage() {
  const { t } = useTranslation();
  usePublicTitle(t('getStarted.title'));
  return (
    <div className="lp">
      <TopNav />
      <main className="lp-section">
        <div className="ui-container lp-cta__inner">
          <h1 className="lp-hero__title">{t('getStarted.title')}</h1>
          <p className="lp-hero__lede">{t('getStarted.body')}</p>
          <div className="lp-hero__cta">
            <ButtonLink to="/login" variant="primary" size="lg">{t('site.login')}</ButtonLink>
            <ButtonLink to="/" variant="secondary" size="lg">{t('common.backHome')}</ButtonLink>
          </div>
        </div>
      </main>
      <Footer />
    </div>
  );
}
