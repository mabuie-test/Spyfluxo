# Spyfluxo

Solução completa para reutilizar telemóveis Android antigos como câmeras de vigilância conectadas a um backend Node.js com MongoDB.

## Estrutura do projeto

```
.
├── android-app/           # Projeto Android compatível com AIDE (sem dependências externas)
│   ├── AndroidManifest.xml
│   ├── res/
│   └── src/com/example/oldphonecamera/
└── backend/               # API Node.js com Socket.IO e MongoDB Atlas
    ├── package.json
    ├── server.js
    ├── .env.example
    └── public/index.html  # Painel web com Leaflet
```

## Aplicativo Android (AIDE)

1. Copie a pasta `android-app` para o dispositivo e abra no AIDE.
2. Atualize `Config.java` com:
   - `BACKEND_URL`: endereço HTTPS público do backend (por exemplo, URL do Render).
   - `DEVICE_ID`: identificador único para cada telefone (ex.: `cam-lobby-01`).
3. Compile e instale. O app solicita permissão de câmera e transmite JPEGs comprimidos ao backend sempre que o botão **Iniciar** estiver ativo.

### Envio opcional de localização

Para incluir coordenadas, adapte a payload enviada em `uploadFrame` adicionando um objeto JSON `location`:

```json
{
  "deviceId": "cam-lobby-01",
  "frame": "<BASE64>",
  "location": {
    "coordinates": {
      "latitude": -23.55,
      "longitude": -46.63
    }
  }
}
```

O backend mantém o último ponto em MongoDB e o painel Leaflet exibirá o marcador correspondente.

## Backend Node.js

### Configuração

```bash
cd backend
npm install
cp .env.example .env
# edite .env e defina MONGODB_URI para o cluster Atlas e PORT se necessário
npm run start
```

O servidor expõe:

- `POST /api/frames` — recebe frames Base64 e atualiza o estado do dispositivo.
- `GET /api/devices` — lista dispositivos conhecidos, último contato e localização.
- `GET /api/frames/:deviceId/latest` — devolve o último frame do dispositivo.
- Socket.IO — canal `frame` em rooms `device:<id>` para streaming em tempo real.

### Deploy no Render

1. Faça fork do backend e crie um serviço Web no Render apontando para o repositório.
2. Defina a variável de ambiente `MONGODB_URI` com as credenciais do Atlas.
3. Configure o comando de execução como `npm install && npm start`.
4. Use a URL gerada pelo Render no `Config.BACKEND_URL` dos dispositivos.

## Painel Web

Disponível em `backend/public/index.html` (servido automaticamente pelo Express). Mostra a lista de dispositivos, último frame capturado e um mapa Leaflet com os pontos registrados.

## Boas práticas

- Utilize redes Wi-Fi estáveis e mantenha os aparelhos conectados à energia.
- Ajuste a taxa de compressão (`bitmap.compress`) conforme a largura de banda.
- Considere adicionar autenticação por token antes de colocar em produção.
