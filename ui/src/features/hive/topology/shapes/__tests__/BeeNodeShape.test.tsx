import { render } from '@testing-library/react';
import React from 'react';
import { describe, test, expect } from 'vitest';
import { BeeNodeShape } from '../BeeNodeShape';
import { roleShape } from '../shapeMap';
import { BeeTitle, Role } from '../../role';
import { statusToColor, BeeColors, Status } from '../../theme';

describe('role to shape mapping', () => {
  test('map contains all roles', () => {
    const roles: Role[] = ['Seeder', 'Gatekeeper', 'Worker', 'Postprocessor', 'Trigger', 'LogAggregator'];
    for (const r of roles) {
      expect(roleShape[r]).toBeDefined();
    }
  });
});

describe('statusToColor', () => {
  const statuses: Status[] = ['ok', 'warn', 'err', 'ghost'];
  for (const s of statuses) {
    test(`status ${s} maps to color`, () => {
      expect(statusToColor(s)).toBe(BeeColors.status[s]);
    });
  }
});

describe('BeeNodeShape snapshots', () => {
  const roles: Role[] = ['Seeder', 'Gatekeeper', 'Worker', 'Postprocessor', 'Trigger', 'LogAggregator'];
  for (const role of roles) {
    test(role, () => {
      const { container } = render(
        <svg>
          <BeeNodeShape role={role} status="ok" title={BeeTitle[role]} name="alpha" />
        </svg>
      );
      expect(container).toMatchSnapshot();
    });
  }
});
