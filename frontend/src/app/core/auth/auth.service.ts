import {
  HttpClient,
  HttpErrorResponse,
  HttpHeaders,
  HttpParams,
} from '@angular/common/http';
import {inject, Injectable, signal} from '@angular/core';
import {firstValueFrom} from 'rxjs';

interface ApiResponse<T> {
  data: T;
}
export interface AuthUser {
  id: string;
  email: string;
  displayName: string;
}
export interface Workspace {
  id: string;
  name: string;
  role: string;
}
interface CsrfToken {
  token: string;
  headerName: string;
}
export type AuthFailureKind =
  | 'unauthorized'
  | 'not-found'
  | 'network'
  | 'server';
export class AuthFailure extends Error {
  constructor(
    readonly kind: AuthFailureKind,
    readonly code?: 'INVALID_RESET_TOKEN',
  ) {
    super(kind);
  }
}

// Validate before publishing a session to shared identity observers.
function sessionUser(value: unknown): AuthUser {
  if (
    !value ||
    typeof value !== 'object' ||
    Array.isArray(value) ||
    Object.keys(value).sort().join(',') !== 'displayName,email,id'
  )
    throw new AuthFailure('server');
  const user = value as Record<string, unknown>;
  if (
    typeof user['id'] !== 'string' ||
    !/^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(
      user['id'],
    ) ||
    typeof user['email'] !== 'string' ||
    !user['email'].trim() ||
    typeof user['displayName'] !== 'string'
  )
    throw new AuthFailure('server');
  for (const [text, max] of [
    [user['email'], 320],
    [user['displayName'], 255],
  ] as const) {
    if (Array.from(text).length > max) throw new AuthFailure('server');
    for (let i = 0; i < text.length; i++) {
      const c = text.charCodeAt(i);
      if (c < 32 || (c >= 127 && c <= 159) || c === 0x2028 || c === 0x2029)
        throw new AuthFailure('server');
      if (c >= 0xd800 && c <= 0xdbff) {
        const next = text.charCodeAt(++i);
        if (!(next >= 0xdc00 && next <= 0xdfff))
          throw new AuthFailure('server');
      } else if (c >= 0xdc00 && c <= 0xdfff) throw new AuthFailure('server');
    }
  }
  return value as AuthUser;
}

@Injectable({providedIn: 'root'})
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly currentUser = signal<AuthUser | null>(null);
  readonly user = this.currentUser.asReadonly();
  private readonly base = '/api/v1/auth';
  private authEpoch = 0;

  private async request<T>(operation: () => Promise<T>): Promise<T> {
    const epoch = this.authEpoch;
    try {
      return await operation();
    } catch (error) {
      if (error instanceof HttpErrorResponse) {
        if (error.status === 401) {
          if (epoch === this.authEpoch) this.currentUser.set(null);
          throw new AuthFailure('unauthorized');
        }
        throw new AuthFailure(
          error.status === 404
            ? 'not-found'
            : error.status === 0
              ? 'network'
              : 'server',
          error.status === 400 &&
          error.error?.error?.code === 'INVALID_RESET_TOKEN'
            ? 'INVALID_RESET_TOKEN'
            : undefined,
        );
      }
      throw error;
    }
  }

  private async csrfHeaders(): Promise<HttpHeaders> {
    const response = await firstValueFrom(
      this.http.get<ApiResponse<CsrfToken>>(`${this.base}/csrf`),
    );
    return new HttpHeaders().set(response.data.headerName, response.data.token);
  }

  session(): Promise<AuthUser> {
    const epoch = this.authEpoch;
    return this.request(async () => {
      const response = await firstValueFrom(
        this.http.get<ApiResponse<AuthUser>>(`${this.base}/session`),
      );
      const user = sessionUser(response.data);
      if (epoch === this.authEpoch) this.currentUser.set(user);
      return user;
    });
  }

  login(email: string, password: string): Promise<AuthUser> {
    const epoch = ++this.authEpoch;
    return this.request(async () => {
      const headers = (await this.csrfHeaders()).set(
        'Content-Type',
        'application/x-www-form-urlencoded',
      );
      const body = new HttpParams()
        .set('username', email)
        .set('password', password);
      const response = await firstValueFrom(
        this.http.post<ApiResponse<AuthUser>>(
          `${this.base}/login`,
          body.toString(),
          {headers},
        ),
      );
      if (epoch === this.authEpoch) this.currentUser.set(response.data);
      return response.data;
    });
  }

  requestRecovery(email: string): Promise<void> {
    return this.request(async () => {
      const headers = await this.csrfHeaders();
      const response = await firstValueFrom(
        this.http.post<ApiResponse<{accepted: boolean}>>(
          `${this.base}/recovery/request`,
          {email},
          {headers, observe: 'response'},
        ),
      );
      if (response.status !== 202 || response.body?.data?.accepted !== true) {
        throw new AuthFailure('server');
      }
    });
  }

  confirmRecovery(token: string, password: string): Promise<void> {
    return this.request(async () => {
      const headers = await this.csrfHeaders();
      const response = await firstValueFrom(
        this.http.post<void>(
          `${this.base}/recovery/confirm`,
          {token, password},
          {headers, observe: 'response'},
        ),
      );
      if (response.status !== 204) throw new AuthFailure('server');
    });
  }

  workspaces(): Promise<Workspace[]> {
    return this.request(
      async () =>
        (
          await firstValueFrom(
            this.http.get<ApiResponse<Workspace[]>>(`${this.base}/workspaces`),
          )
        ).data,
    );
  }

  getWorkspace(id: string): Promise<Workspace> {
    return this.request(
      async () =>
        (
          await firstValueFrom(
            this.http.get<ApiResponse<Workspace>>(
              `${this.base}/workspaces/${encodeURIComponent(id)}`,
            ),
          )
        ).data,
    );
  }

  logout(): Promise<void> {
    const epoch = ++this.authEpoch;
    return this.request(async () => {
      const headers = await this.csrfHeaders();
      await firstValueFrom(
        this.http.post<void>(`${this.base}/logout`, null, {headers}),
      );
      if (epoch === this.authEpoch) this.currentUser.set(null);
    });
  }
}
