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
export type AuthFailureKind = 'unauthorized' | 'network' | 'server';
export class AuthFailure extends Error {
  constructor(
    readonly kind: AuthFailureKind,
    readonly code?: 'INVALID_RESET_TOKEN',
  ) {
    super(kind);
  }
}

@Injectable({providedIn: 'root'})
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly currentUser = signal<AuthUser | null>(null);
  readonly user = this.currentUser.asReadonly();
  private readonly base = '/api/v1/auth';

  private async request<T>(operation: () => Promise<T>): Promise<T> {
    try {
      return await operation();
    } catch (error) {
      if (error instanceof HttpErrorResponse) {
        if (error.status === 401) {
          this.currentUser.set(null);
          throw new AuthFailure('unauthorized');
        }
        throw new AuthFailure(
          error.status === 0 ? 'network' : 'server',
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
    return this.request(async () => {
      const response = await firstValueFrom(
        this.http.get<ApiResponse<AuthUser>>(`${this.base}/session`),
      );
      this.currentUser.set(response.data);
      return response.data;
    });
  }

  login(email: string, password: string): Promise<AuthUser> {
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
      this.currentUser.set(response.data);
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

  logout(): Promise<void> {
    return this.request(async () => {
      const headers = await this.csrfHeaders();
      await firstValueFrom(
        this.http.post<void>(`${this.base}/logout`, null, {headers}),
      );
      this.currentUser.set(null);
    });
  }
}
