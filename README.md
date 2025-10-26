# Spyfluxo

Solução completa para transformar telemóveis Android antigos em câmeras IP com streaming de vídeo **+ áudio**, autenticação multiusuário e acompanhamento em tempo real via painel web Leaflet.

## Estrutura do projeto

```
.
├── android-app/           # Projeto Android compatível com AIDE (sem dependências externas)
│   ├── AndroidManifest.xml
│   ├── res/
│   └── src/com/example/oldphonecamera/
└── backend/               # API Node.js + MongoDB com painel web integrado
    ├── package.json
    ├── server.js
    ├── .env.example
    └── public/index.html  # Painel com autenticação, mapa Leaflet e player de múltiplas câmeras
```

---

## Aplicativo Android (AIDE friendly)

1. Copie a pasta `android-app` para o dispositivo e abra no AIDE.
2. Compile e instale o app (não há dependências externas além do SDK padrão).
3. Ao abrir o aplicativo:
   - Informe a URL do backend, suas credenciais e um nome único para a câmera.
   - O app faz login, regista (ou rotaciona) a chave da câmera e guarda o `deviceId`/`deviceKey` em `SharedPreferences`.
   - Um toque em **Iniciar** cria segmentos MP4 de ~15 s com áudio AAC + vídeo H.264, envia para o backend e recomeça automaticamente.
   - **Parar** conclui o segmento atual e retorna ao modo de preview.
   - **Sair** limpa a sessão e volta ao ecrã de autenticação.

### Permissões

O Android pede câmera e microfone. O app mantém a tela ligada durante a captura e envia os arquivos diretamente para o backend via `HttpURLConnection` (sem bibliotecas de terceiros).

### Personalizações rápidas

- Duração dos segmentos: ajuste `Config.SEGMENT_DURATION_MS`.
- Resolução/bitrate: altere as chamadas de `MediaRecorder` em `MainActivity` conforme a necessidade de banda.
- Localização: envie `location: { lat, lng }` ao chamar a API `ApiClient.uploadSegment` (o backend já persiste e exibe no mapa caso fornecido).

---

## Backend Node.js

### Dependências

- Node.js 18+
- MongoDB (Atlas ou instância própria)

### Configuração

```bash
cd backend
npm install
cp .env.example .env
# edite .env com:
#   MONGODB_URI=mongodb+srv://...
#   JWT_SECRET=uma_frase_segura
npm run start
```

Por padrão o servidor expõe o painel web em `http://localhost:3000/`.

### Modelo de segurança

- Utilizadores registam-se (ou são criados manualmente) com email/senha. As senhas ficam com `bcrypt` e o login devolve um JWT.
- Cada utilizador pode gerar múltiplas câmeras. Ao “registrar dispositivo” a API cria/rotaciona um `deviceKey` exclusivo (armazenado com hash + fingerprint) para autenticar uploads.
- As câmeras enviam segmentos MP4 via `POST /api/segments` usando `Authorization: Bearer <deviceKey>`.
- O painel e chamadas REST usam `Authorization: Bearer <jwt>`.

### Endpoints principais

| Método | Caminho | Descrição |
| ------ | ------- | --------- |
| `POST` | `/api/auth/register` | Regista novo utilizador (nome, email, senha). |
| `POST` | `/api/auth/login` | Retorna JWT + dados do utilizador. |
| `GET`  | `/api/me` | Dados do utilizador autenticado. |
| `POST` | `/api/devices/register` | Gera/rotaciona credenciais para uma câmera do utilizador. |
| `GET`  | `/api/devices` | Lista as câmeras do utilizador com `lastSeen` e localização. |
| `POST` | `/api/segments` | Upload de segmentos MP4 (dispositivos autenticados). |
| `GET`  | `/api/segments/:deviceId` | Histórico recente (até 25 segmentos) de uma câmera. |
| `GET`  | `/api/segments/:deviceId/latest` | Último segmento com dados base64. |

Eventos Socket.IO:

- `authenticate` (cliente → servidor) — envia o JWT para habilitar a sessão.
- `subscribe-device` (cliente → servidor) — junta-se à sala da câmera.
- `segment` (servidor → cliente) — notifica novos segmentos (id, duração, tamanho, timestamp ISO).

### Painel web (backend/public/index.html)

Funcionalidades:

- Login por email/senha (usando o mesmo backend do app Android).
- Registo de novas câmeras com exibição imediata do par `deviceId`/`deviceKey` (copie para o app em cada telefone).
- Lista de câmeras com estado online/offline e último contacto.
- Player HTML5 que reproduz os segmentos MP4 com áudio.
- Galeria de segmentos recentes com metadados (duração, tamanho, horário).
- Mapa Leaflet com marcadores para cada câmera que envia localização.
- Atualização em tempo real via Socket.IO quando chegam novos segmentos.

### Dicas de implantação (Render/Atlas)

1. Faça deploy do diretório `backend` num serviço Node (Render, Railway, Heroku, etc.).
2. Defina `MONGODB_URI` e `JWT_SECRET` nas variáveis de ambiente.
3. Garanta que o plano suporta uploads HTTP de até ~50 MB (valor configurado no `express.json`).
4. Aponte o aplicativo Android para a URL HTTPS pública do backend.

---

## Fluxo sugerido

1. Registe um utilizador (ex.: via `curl`):
   ```bash
   curl -X POST https://SEU_BACKEND/api/auth/register \
     -H 'Content-Type: application/json' \
     -d '{"name":"Admin","email":"admin@example.com","password":"senhaSegura"}'
   ```
2. Faça login pelo terminal para obter o JWT e reutilizá-lo nas chamadas autenticadas:
   ```bash
   # Login: devolve { token, user }
   curl -X POST https://SEU_BACKEND/api/auth/login \
     -H 'Content-Type: application/json' \
     -d '{"email":"admin@example.com","password":"senhaSegura"}'

   # Guarde o token numa variável de shell (Bash) — requer `jq` instalado:
   JWT=$(curl -s -X POST https://SEU_BACKEND/api/auth/login \
     -H 'Content-Type: application/json' \
     -d '{"email":"admin@example.com","password":"senhaSegura"}' | jq -r '.token')

   # Exemplo de chamada autenticada: obter os dados do utilizador logado
   curl https://SEU_BACKEND/api/me \
     -H "Authorization: Bearer $JWT"
   ```
3. No painel web, faça login e gere uma câmera chamada “Sala”. Copie o `deviceId` e o `deviceKey`.
4. No Android, informe o backend, email/senha e o nome “Sala”. O app guardará as credenciais retornadas automaticamente.
5. Pressione **Iniciar** para começar a transmitir. O painel exibirá o vídeo com áudio e adicionará a câmera no mapa.

---

## Boas práticas

- Mantenha os telemóveis ligados à corrente e com boa ventilação.
- Ajuste bitrate/resolução ao perfil da rede para reduzir latência.
- Configure HTTPS/TLS e utilize JWTs com expiração curta em produção.
- Faça rotação periódica das chaves (`/api/devices/register`) para cada câmera.
- Considere adicionar mecanismos de limpeza automática de segmentos antigos no MongoDB (TTL ou tarefas agendadas).
