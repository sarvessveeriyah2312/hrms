import { core } from './core';
import { landing } from './landing';
import { dashboard } from './dashboard';
import { approvals } from './approvals';
import { leave } from './leave';
import { leaveSettings } from './leaveSettings';
import { misc } from './misc';
import { admin } from './admin';
import { workflows } from './workflows';
import { claims } from './claims';
import { sites } from './sites';
import { today } from './today';
import { timesheet } from './timesheet';

/** English: the source language. Malay and Chinese must have every key (checked by the typecheck). */
export const en = { ...core, ...landing, ...dashboard, ...approvals, ...leave, ...leaveSettings, ...misc, ...admin, ...workflows, ...claims, ...sites, ...today, ...timesheet };
