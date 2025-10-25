require('dotenv').config();

const express = require('express');
const http = require('http');
const { MongoClient } = require('mongodb');
const { Server } = require('socket.io');
const path = require('path');

const app = express();
const server = http.createServer(app);
const io = new Server(server, {
  cors: {
    origin: '*'
  }
});

app.use(express.json({ limit: '5mb' }));
app.use(express.static(path.join(__dirname, 'public')));

const mongoUri = process.env.MONGODB_URI;
const port = process.env.PORT || 3000;

if (!mongoUri) {
  console.error('MONGODB_URI não configurada. Crie um arquivo .env com a variável.');
  process.exit(1);
}

let db;
let framesCollection;
let devicesCollection;

async function start() {
  const client = new MongoClient(mongoUri);
  await client.connect();
  db = client.db();
  framesCollection = db.collection('frames');
  devicesCollection = db.collection('devices');

  await framesCollection.createIndex({ deviceId: 1, createdAt: -1 });
  await devicesCollection.createIndex({ deviceId: 1 }, { unique: true });

  server.listen(port, () => {
    console.log(`Servidor escutando na porta ${port}`);
  });
}

io.on('connection', socket => {
  socket.on('subscribe-device', deviceId => {
    socket.join(`device:${deviceId}`);
  });
});

app.post('/api/frames', async (req, res) => {
  const { deviceId, frame, location } = req.body;
  if (!deviceId || !frame) {
    return res.status(400).json({ error: 'deviceId e frame são obrigatórios' });
  }

  const now = new Date();
  const frameDoc = {
    deviceId,
    frame,
    createdAt: now,
    location: location || null
  };

  try {
    await framesCollection.insertOne(frameDoc);
    await devicesCollection.updateOne(
      { deviceId },
      {
        $set: {
          deviceId,
          lastSeen: now,
          location: location || null
        }
      },
      { upsert: true }
    );
    io.to(`device:${deviceId}`).emit('frame', frameDoc);
    res.json({ ok: true });
  } catch (error) {
    console.error('Erro ao guardar frame', error);
    res.status(500).json({ error: 'Erro ao guardar frame' });
  }
});

app.get('/api/devices', async (_req, res) => {
  const devices = await devicesCollection
    .find({}, { projection: { _id: 0 } })
    .sort({ lastSeen: -1 })
    .toArray();
  res.json(devices);
});

app.get('/api/frames/:deviceId/latest', async (req, res) => {
  const { deviceId } = req.params;
  const latest = await framesCollection
    .find({ deviceId })
    .sort({ createdAt: -1 })
    .limit(1)
    .next();

  if (!latest) {
    return res.status(404).json({ error: 'Sem frames' });
  }
  res.json({ frame: latest.frame, createdAt: latest.createdAt });
});

start().catch(error => {
  console.error('Erro fatal ao iniciar servidor', error);
  process.exit(1);
});
