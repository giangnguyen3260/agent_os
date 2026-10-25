import {Component, input} from '@angular/core';
// Figma SDS React InputField/Button mappings have no installed Angular equivalent.
// Use existing native Forest auth/member controls; no React/SDS dependency added.
@Component({
  selector: 'app-invitation-scope',
  standalone: true,
  template: `<aside aria-label="Phạm vi lời mời">
    <h2>Tổ chức: {{ organizationName() }}</h2>
    <p>Vai trò được mời: MEMBER</p>
    <p>Chỉ tham gia tổ chức. Không thêm vào Team hoặc cấp quyền duyệt.</p>
    <p>
      Quyền duyệt riêng còn hạn có thể tiếp tục có hiệu lực khi tham gia lại.
    </p>
  </aside>`,
  styles: `
    :host {
      display: block;
      min-width: 0;
    }
    aside {
      display: flex;
      flex-direction: column;
      gap: 8px;
      padding: 12px;
      background: var(--surface-soft);
      border: 1px solid var(--line);
      border-radius: 8px;
      color: var(--text);
    }
    h2,
    p {
      margin: 0;
      overflow-wrap: anywhere;
    }
    h2 {
      font-size: 18px;
      line-height: 26px;
      font-weight: 600;
    }
    p {
      font-size: 14px;
      line-height: 22px;
    }
  `,
})
export class InvitationScopeComponent {
  readonly organizationName = input.required<string>();
}
