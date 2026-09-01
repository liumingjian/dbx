import { describe, expect, it } from 'vitest';
import { isWizardStage, paths, routePatterns } from './paths';

describe('route paths', () => {
  it('builds each parameterised URL from its own pattern', () => {
    expect(paths.wizardStage('draft-1', 'scope')).toBe('/tasks/new/draft-1/scope');
    expect(paths.migrationRun('run-7')).toBe('/runs/run-7');
    expect(paths.tableMigrationUnit('run-7', 'unit-3')).toBe('/runs/run-7/tables/unit-3');
  });

  it('leaves no parameter placeholder behind', () => {
    for (const url of [
      paths.wizardStage('d', 'confirm'),
      paths.migrationRun('r'),
      paths.tableMigrationUnit('r', 'u'),
    ]) {
      expect(url).not.toContain(':');
    }
  });

  it('escapes identifiers so they cannot alter the route shape', () => {
    expect(paths.migrationRun('run/7')).toBe('/runs/run%2F7');
  });

  it('recognises only the six wizard stages', () => {
    expect(isWizardStage('scope')).toBe(true);
    expect(isWizardStage('nonsense')).toBe(false);
    expect(isWizardStage(undefined)).toBe(false);
  });

  it('keeps a pattern for every route the router mounts', () => {
    expect(Object.values(routePatterns).every((pattern) => pattern.startsWith('/'))).toBe(true);
  });
});
