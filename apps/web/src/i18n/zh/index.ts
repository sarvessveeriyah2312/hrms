import type { en } from '../en';
import type { Shape } from '../shape';
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

/** Simplified Chinese. Every English key must be here (checked by the typecheck). */
export const zh: Shape<typeof en> = { ...core, ...landing, ...dashboard, ...approvals, ...leave, ...leaveSettings, ...misc, ...admin, ...workflows, ...claims, ...sites };
