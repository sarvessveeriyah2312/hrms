import { Icon, type IconName } from './Icon';

/** One colour per module, used the same way everywhere so people learn them: leave is always teal, claims always orange. */
export type Tone = 'blue' | 'green' | 'orange' | 'teal' | 'purple' | 'pink';

type Props = { icon: IconName; tone: Tone; size?: 'sm' | 'md' | 'lg' };

export function Glyph({ icon, tone, size = 'md' }: Props) {
  const px = size === 'sm' ? 12 : size === 'lg' ? 20 : 14;
  return (
    <span className={`ui-glyph ui-glyph--${tone} ui-glyph--${size}`} aria-hidden="true">
      <Icon name={icon} size={px} strokeWidth={2.25} />
    </span>
  );
}
