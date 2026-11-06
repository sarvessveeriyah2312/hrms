import type { ShiftTone } from '@hrms/api-client';
import { addDays } from '../../lib/dates';

export const SHIFT_TONES: ShiftTone[] = ['blue', 'green', 'orange', 'teal', 'purple', 'pink'];

/** "09:00" from "09:00:00". */
export const hhmm = (time: string | null | undefined) => (time ? time.slice(0, 5) : '');

/** The Monday of the week a date (YYYY-MM-DD) is in. */
export function mondayOf(iso: string) {
  const day = new Date(`${iso}T00:00`).getDay(); // 0 Sunday … 6 Saturday
  return addDays(iso, -((day + 6) % 7));
}

/** Paid minutes of a shift: its length less the break. */
export function shiftHours(start: string, end: string, breakMin: number) {
  const m = (x: string) => Number(x.slice(0, 2)) * 60 + Number(x.slice(3, 5));
  return Math.max(0, m(end) - m(start) - breakMin);
}
