import { Injectable, signal } from '@angular/core';
import { Client } from '@stomp/stompjs';

@Injectable({ providedIn: 'root' })
export class StompService {
  private client: Client;
  readonly connected = signal(false);

  constructor() {
    this.client = new Client({ brokerURL: 'ws://localhost:15674/ws' });
    this.client.onConnect = () => this.connected.set(true);
    this.client.onDisconnect = () => this.connected.set(false);
    this.client.activate();
  }
}
