/* UI-only onboarding dismissal for disposable test homes. No API key or login. */
exports.dismissOnboarding = async function dismissOnboarding(page) {
  const deadline = Date.now() + 10000;
  while (Date.now() < deadline) {
    const continueButton = page.getByRole('button', {name: /^(继续|Continue)$/});
    if (await continueButton.isVisible().catch(() => false)
        && await continueButton.isEnabled().catch(() => false)) {
      await continueButton.click({timeout: 2000});
    }
    const welcome = page.getByRole('dialog', {name: /^(开始使用|Get started)$/});
    if (await welcome.isVisible().catch(() => false)) {
      await welcome.getByRole('button', {name: /^(关闭|Close)$/}).click();
      await welcome.waitFor({state: 'hidden'});
      return;
    }
    await page.waitForTimeout(100);
  }
  // A previously onboarded disposable instance may not show a welcome dialog.
  if (await page.locator('[role="dialog"][aria-modal="true"]').count()) {
    throw new Error('Unexpected startup modal; do not click through unknown dialogs');
  }
};
