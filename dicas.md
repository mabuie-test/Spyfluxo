# Criar uma conta via curl

Siga estes passos para registar um novo utilizador no backend:

1. Substitua `SEU_BACKEND` pela URL pública (ou `http://localhost:3000` em ambiente local).
2. Execute o comando abaixo num terminal com `curl` instalado:

```bash
curl -X POST https://SEU_BACKEND/api/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"name":"Nome do Utilizador","email":"utilizador@example.com","password":"UmaSenhaForte123"}'
```

Se a chamada for bem-sucedida, o backend devolve um JSON com os dados básicos do utilizador criado. Utilize depois o endpoint de login para obter o token JWT e aceder às rotas protegidas.
