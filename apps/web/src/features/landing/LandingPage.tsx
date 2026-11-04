import { useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { TopNav } from '../../components/TopNav';
import { Footer } from '../../components/Footer';
import { ButtonLink } from '../../components/Button';
import { Glyph } from '../../components/Glyph';
import { Icon } from '../../components/Icon';
import { PRODUCT_NAME } from '../../brand';
import { HeroPreview } from './HeroPreview';
import { APPROVAL_STEPS, CLOCK_METHODS, COUNTRIES, FOR_EMPLOYEES, FOR_MANAGERS, type Feature } from './content';
import './landing.css';

function FeatureList<K extends string>({ items, text }: { items: Feature<K>[]; text: (key: K) => { title: string; body: string } }) {
  return (
    <ul className="lp-flist">
      {items.map((f) => (
        <li key={f.key}>
          <Glyph icon={f.icon} tone={f.tone} size="lg" />
          <div>
            <h4>{text(f.key).title}</h4>
            <p>{text(f.key).body}</p>
          </div>
        </li>
      ))}
    </ul>
  );
}

export function LandingPage() {
  const { t, i18n } = useTranslation();

  useEffect(() => {
    document.title = t('landing.docTitle', { product: PRODUCT_NAME });
  }, [t, i18n.language]);

  const links = [
    { href: '#features', label: t('site.links.features') },
    { href: '#clock-in', label: t('site.links.clockIn') },
    { href: '#approvals', label: t('site.links.approvals') },
    { href: '#countries', label: t('site.links.countries') },
  ];

  return (
    <div className="lp">
      <a className="lp-skip" href="#main">{t('site.skip')}</a>
      <TopNav links={links} />

      <main id="main">
        {/* Hero: plain promise, two entry points, then the product itself on the gradient stage. */}
        <section className="lp-hero">
          <div className="ui-container lp-hero__text">
            <h1 className="lp-hero__title">{t('landing.heroTitle')}</h1>
            <p className="lp-hero__lede">{t('landing.heroLede')}</p>
            <div className="lp-hero__cta">
              <ButtonLink to="/get-started" variant="primary" size="lg">{t('site.getStarted')}</ButtonLink>
              <ButtonLink to="/login" variant="secondary" size="lg">{t('site.login')}</ButtonLink>
            </div>
          </div>

          <div className="lp-stage">
            <div className="ui-container">
              <div className="lp-stage__frame">
                <HeroPreview />
              </div>
              <p className="lp-stage__hint">{t('landing.tryIt')}</p>
            </div>
          </div>
        </section>

        <section id="features" className="lp-section">
          <div className="ui-container">
            <header className="lp-head">
              <h2>{t('landing.featuresTitle')}</h2>
              <p>{t('landing.featuresLede')}</p>
            </header>
            <div className="lp-split">
              <div className="lp-panel">
                <h3 className="lp-panel__title"><Icon name="tablet" size={18} /> {t('landing.onPhone')}</h3>
                <FeatureList items={FOR_EMPLOYEES} text={(k) => ({ title: t(`landing.employees.${k}.title`), body: t(`landing.employees.${k}.body`) })} />
              </div>
              <div className="lp-panel">
                <h3 className="lp-panel__title"><Icon name="chart" size={18} /> {t('landing.onWeb')}</h3>
                <FeatureList items={FOR_MANAGERS} text={(k) => ({ title: t(`landing.managers.${k}.title`), body: t(`landing.managers.${k}.body`) })} />
              </div>
            </div>
          </div>
        </section>

        <section id="clock-in" className="lp-section lp-section--tint">
          <div className="ui-container">
            <header className="lp-head">
              <h2>{t('landing.clockTitle')}</h2>
              <p>{t('landing.clockLede')}</p>
            </header>
            <ul className="lp-methods">
              {CLOCK_METHODS.map((m) => (
                <li key={m.key}>
                  <Glyph icon={m.icon} tone={m.tone} size="lg" />
                  <h3>{t(`landing.methods.${m.key}.title`)}</h3>
                  <p>{t(`landing.methods.${m.key}.body`)}</p>
                </li>
              ))}
            </ul>
            <div id="privacy" className="lp-privacy">
              <Icon name="shield" size={22} />
              <p>
                <strong>{t('landing.privacyStrong')}</strong> {t('landing.privacyRest')}
              </p>
            </div>
          </div>
        </section>

        <section id="approvals" className="lp-section">
          <div className="ui-container">
            <header className="lp-head">
              <h2>{t('landing.approvalsTitle')}</h2>
              <p>{t('landing.approvalsLede')}</p>
            </header>
            <ol className="lp-chain">
              {APPROVAL_STEPS.map((s, i) => (
                <li key={s}>
                  <span className="lp-chain__num" aria-hidden="true">{i + 1}</span>
                  <h3>{t(`landing.steps.${s}.title`)}</h3>
                  <p>{t(`landing.steps.${s}.body`)}</p>
                </li>
              ))}
            </ol>
            <p className="lp-note">{t('landing.backupNote')}</p>
          </div>
        </section>

        <section id="countries" className="lp-section lp-section--tint">
          <div className="ui-container lp-countries">
            <header className="lp-head lp-head--side">
              <h2>{t('landing.countriesTitle')}</h2>
              <p>{t('landing.countriesLede')}</p>
            </header>
            <table className="lp-table">
              <caption className="lp-visually-hidden">{t('landing.countriesCaption')}</caption>
              <thead>
                <tr><th scope="col">{t('landing.country')}</th><th scope="col">{t('landing.taxDocs')}</th></tr>
              </thead>
              <tbody>
                {COUNTRIES.map((c) => (
                  <tr key={c}>
                    <th scope="row"><span className="lp-code" aria-hidden="true">{c}</span>{t(`landing.countries.${c}.name`)}</th>
                    <td>{t(`landing.countries.${c}.docs`)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>

        <section className="lp-cta">
          <div className="ui-container lp-cta__inner">
            <h2>{t('landing.ctaTitle')}</h2>
            <div className="lp-hero__cta">
              <ButtonLink to="/get-started" variant="primary" size="lg">{t('site.getStarted')}</ButtonLink>
              <ButtonLink to="/login" variant="secondary" size="lg">{t('site.login')}</ButtonLink>
            </div>
          </div>
        </section>
      </main>

      <Footer />
    </div>
  );
}
