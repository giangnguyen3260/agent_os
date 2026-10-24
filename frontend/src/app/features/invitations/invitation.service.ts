import {HttpClient, HttpHeaders} from '@angular/common/http';
import {inject, Injectable} from '@angular/core';
import {firstValueFrom, timeout} from 'rxjs';
import {
  Acceptance,
  canonicalCommand,
  Correlation,
  exact,
  envelope,
  Inspection,
  InvitationContext,
  InvitationPage,
  Receipt,
  Status,
  validAcceptance,
  validContext,
  validInspection,
  validPage,
  validReceipt,
} from './invitation-validation';
export type CommandBody =
  | Readonly<{email: string; acknowledgement: true}>
  | Readonly<{expectedRevision: number; acknowledgement: true}>;
export interface Command {
  readonly correlation: Readonly<Correlation>;
  readonly body: CommandBody;
}
export class InvalidInvitationResponse extends Error {}
@Injectable({providedIn: 'root'})
export class InvitationService {
  private readonly http = inject(HttpClient);
  private base(org: string): string {
    return `/api/v1/organizations/${encodeURIComponent(org)}/invitations`;
  }
  private async data(request: ReturnType<HttpClient['get']>): Promise<unknown> {
    const value: unknown = await firstValueFrom(request.pipe(timeout(30000)));
    if (!envelope(value)) throw new InvalidInvitationResponse();
    return value['data'];
  }
  private async headers(): Promise<HttpHeaders> {
    const v = await this.data(this.http.get('/api/v1/auth/csrf'));
    if (
      !exact(v, 'token headerName') ||
      typeof v['token'] !== 'string' ||
      !v['token'] ||
      typeof v['headerName'] !== 'string' ||
      !/^[A-Za-z0-9-]+$/.test(v['headerName'])
    )
      throw new InvalidInvitationResponse();
    return new HttpHeaders().set(v['headerName'], v['token']);
  }
  async list(
    org: string,
    page: number,
    status: Status,
  ): Promise<InvitationPage> {
    const v = await this.data(
      this.http.get(this.base(org), {params: {page, status}}),
    );
    if (!validPage(v, org, page, status)) throw new InvalidInvitationResponse();
    return v;
  }
  async context(org: string, id: string): Promise<InvitationContext> {
    const v = await this.data(
      this.http.get(`${this.base(org)}/${encodeURIComponent(id)}/context`),
    );
    if (!validContext(v, org, id)) throw new InvalidInvitationResponse();
    return v;
  }
  async dispatch(command: Command, dispatched: () => void): Promise<Receipt> {
    const c = command.correlation;
    canonicalCommand(c, command.body);
    const headers = (await this.headers()).set('Idempotency-Key', c.key);
    const suffix =
      c.operation === 'CREATE_INVITATION'
        ? ''
        : `/${encodeURIComponent(c.invitationId || '')}/${c.operation === 'RESEND_INVITATION' ? 'resend' : 'revoke'}`;
    dispatched();
    const v = await this.data(
      this.http.post(this.base(c.organizationId) + suffix, command.body, {
        headers,
      }),
    );
    if (!validReceipt(v, c)) throw new InvalidInvitationResponse();
    return v;
  }
  async receipt(c: Correlation): Promise<Receipt | null> {
    const v = await this.data(
      this.http.get(
        `${this.base(c.organizationId)}/receipts/${encodeURIComponent(c.key)}`,
      ),
    );
    if (v === null) return null;
    if (!validReceipt(v, c)) throw new InvalidInvitationResponse();
    return v;
  }
  async inspect(token: string): Promise<Inspection> {
    const headers = await this.headers();
    const v = await this.data(
      this.http.post('/api/v1/auth/invitations/inspect', {token}, {headers}),
    );
    if (!validInspection(v)) throw new InvalidInvitationResponse();
    return v;
  }
  async accept(
    token: string,
    displayName: string | null,
    password: string | null,
    org: string,
    dispatched: () => void,
  ): Promise<Acceptance> {
    const headers = await this.headers();
    dispatched();
    const v = await this.data(
      this.http.post(
        '/api/v1/auth/invitations/accept',
        {token, displayName, password},
        {headers},
      ),
    );
    if (!validAcceptance(v, org, displayName !== null))
      throw new InvalidInvitationResponse();
    return v;
  }
}
