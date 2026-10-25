// Synthetic same-document navigation for parameter-reuse guard tests.
// Match Angular computed history bookkeeping; this is not a real Back/Forward probe.
export async function pushSpaHistory(page, target) {
  await page.evaluate(path => {
    const state = history.state;
    if (!Number.isSafeInteger(state?.ɵrouterPageId))
      throw new Error('SPA history fixture requires an Angular router page ID');
    history.pushState(
      {...state, ɵrouterPageId: state.ɵrouterPageId + 1},
      '',
      path,
    );
    window.dispatchEvent(new PopStateEvent('popstate', {state: history.state}));
  }, target);
}
