import { Component } from '@angular/core';
import { StompService } from './stomp.service';

@Component({
  selector: 'app-root',
  templateUrl: './app.html',
  styleUrl: './app.css'
})
export class App {
  constructor(public stomp: StompService) {}
}
