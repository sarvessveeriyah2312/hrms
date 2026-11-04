import type { ButtonHTMLAttributes, ReactNode } from 'react';
import { Link, type LinkProps } from 'react-router';

type Variant = 'primary' | 'secondary' | 'ghost';
type Size = 'sm' | 'md' | 'lg';

type Look = { variant?: Variant; size?: Size };

function classes({ variant = 'secondary', size = 'md' }: Look, extra?: string) {
  return ['ui-btn', `ui-btn--${variant}`, `ui-btn--${size}`, extra].filter(Boolean).join(' ');
}

/** Pill button. Use `ButtonLink` when it navigates, `Button` when it acts on the page. */
export function Button({ variant, size, className, type = 'button', ...rest }: ButtonHTMLAttributes<HTMLButtonElement> & Look) {
  return <button type={type} className={classes({ variant, size }, className)} {...rest} />;
}

export function ButtonLink({ variant, size, className, children, ...rest }: LinkProps & Look & { children: ReactNode }) {
  return (
    <Link className={classes({ variant, size }, className)} {...rest}>
      {children}
    </Link>
  );
}
