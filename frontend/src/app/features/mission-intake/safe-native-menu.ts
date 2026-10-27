export class SafeNativeMenu {
  constructor(private readonly root: () => HTMLElement) {}
  open() {
    const dialog = this.root().querySelector('dialog');
    if (dialog && !dialog.open) dialog.showModal();
  }
  close() {
    const dialog = this.root().querySelector('dialog');
    if (dialog?.open) dialog.close();
    this.root()
      .querySelector<HTMLElement>('[aria-controls="mission-intake-menu"]')
      ?.focus();
  }
}
