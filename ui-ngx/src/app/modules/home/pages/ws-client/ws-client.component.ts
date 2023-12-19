///
/// Copyright © 2016-2023 The Thingsboard Authors
///
/// Licensed under the Apache License, Version 2.0 (the "License");
/// you may not use this file except in compliance with the License.
/// You may obtain a copy of the License at
///
///     http://www.apache.org/licenses/LICENSE-2.0
///
/// Unless required by applicable law or agreed to in writing, software
/// distributed under the License is distributed on an "AS IS" BASIS,
/// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
/// See the License for the specific language governing permissions and
/// limitations under the License.
///

// @ts-nocheck

import { Component } from '@angular/core';
import { PageComponent } from '@shared/components/page.component';
import { AppState } from '@core/core.state';
import { Store } from '@ngrx/store';
import { WsClientService } from '@core/http/ws-client.service';
import { MatDialog } from '@angular/material/dialog';
import {
  WsClientConnectionDialogComponent,
  AddWsClientConnectionDialogData
} from '@home/pages/ws-client/ws-client-connection-dialog.component';
import { isDefinedAndNotNull } from '@core/utils';

@Component({
  selector: 'tb-ws-client',
  templateUrl: './ws-client.component.html',
  styleUrls: ['./ws-client.component.scss']
})
export class WsClientComponent extends PageComponent {

  clients:any = [];
  client: any;

  subscriptions: any = [];
  subscription: any;

  constructor(protected store: Store<AppState>,
              private dialog: MatDialog,
              private wsClientService: WsClientService) {
    super(store);
    this.wsClientService.allConnections$.subscribe(value => {
      this.clients = value;
    })
    this.wsClientService.getConnections().subscribe(value => {
      const data = value.data;
      this.clients = data;
      if (data?.length) {
        this.client = data[0];
      } else {
        this.client = null;
      }
    });
  }

  addConnection() {
    const data = {
      connection: {
        "name": "Works 33",
        "uri": "ws://",
        "host": "localhost",
        "port": 8084,
        "path": "/mqtt",
        "clientId": null,
        "username": null,
        "password": "tbmq_dev",
        "keepAlive": 60,
        "reconnectPeriod": 1000,
        "connectTimeout": 30000,
        "clean": true,
        "protocolVersion": "5",
        "properties": {
          "sessionExpiryInterval": null,
          "receiveMaximum": null,
          "maximumPacketSize": null,
          "topicAliasMaximum": null,
          "requestResponseInformation": null,
          "requestProblemInformation": null,
          "userProperties": null
        },
        "userProperties": null,
        "will": null
      }
    };
    this.dialog.open<WsClientConnectionDialogComponent, AddWsClientConnectionDialogData>(WsClientConnectionDialogComponent, {
      disableClose: true,
      panelClass: ['tb-dialog', 'tb-fullscreen-dialog'],
      data
    }).afterClosed()
      .subscribe((res) => {
        if (isDefinedAndNotNull(res)) {
          this.wsClientService.addConnection(res);
        }
      });
  }
}
