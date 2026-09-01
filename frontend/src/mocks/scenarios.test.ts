import { describe, expect, it } from 'vitest';
import { DEFAULT_SCENARIO_ID, resolveScenario, scenarioIds, scenarios } from './scenarios';
import type { StateCoverage } from './scenarios';

describe('URL scenario parameter', () => {
  it('serves the default scenario when the URL names none', () => {
    const resolved = resolveScenario('');
    expect(resolved.definition.id).toBe(DEFAULT_SCENARIO_ID);
    expect(resolved.requested).toBe(false);
  });

  it('selects a scenario by id', () => {
    expect(resolveScenario('?scenario=error').definition.id).toBe('error');
  });

  it('reports an unknown scenario rather than swallowing it', () => {
    // A mistyped scenario in a review link must be visible, not quietly served as default.
    const resolved = resolveScenario('?scenario=nonesuch');
    expect(resolved.unknownScenarioId).toBe('nonesuch');
    expect(resolved.definition.id).toBe(DEFAULT_SCENARIO_ID);
  });

  it('lets the URL override the clock rate', () => {
    expect(resolveScenario('?clockRate=1').clockRate).toBe(1);
    expect(resolveScenario('?scenario=error&clockRate=0').clockRate).toBe(0);
  });

  it('gives every resolution a key that changes with the scenario and the rate', () => {
    const a = resolveScenario('?scenario=error&clockRate=1');
    const b = resolveScenario('?scenario=error&clockRate=2');
    expect(a.key).not.toBe(b.key);
  });

  it('keeps scenario runs from poisoning each other', () => {
    // Only the default scenario persists a draft; everything a review or a test reaches by
    // URL is memory backed, so one run cannot leave state behind for the next.
    for (const id of scenarioIds()) {
      const definition = scenarios.get(id);
      expect(definition?.draftPersistence).toBe(id === DEFAULT_SCENARIO_ID ? 'browser' : 'memory');
    }
  });

  it('can reach all six coverage states #42 requires', () => {
    const required: StateCoverage[] = [
      'loading',
      'empty',
      'error',
      'blocked',
      'inconclusive',
      'disposed',
    ];
    const covered = new Set([...scenarios.values()].flatMap((entry) => entry.covers));
    expect([...required].filter((state) => !covered.has(state))).toEqual([]);
  });
});
