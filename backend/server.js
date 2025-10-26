require('dotenv').config();

const express = require('express');
const http = require('http');
const path = require('path');
const cors = require('cors');
const { MongoClient, ObjectId } = require('mongodb');
const { Server } = require('socket.io');
const bcrypt = require('bcryptjs');
const jwt = require('jsonwebtoken');
const crypto = require('crypto');

const app = express();
const server = http.createServer(app);
const io = new Server(server, {
  cors: {
    origin: '*'
  }
});

app.use(cors());
app.use(express.json({ limit: '50mb' }));
app.use(express.static(path.join(__dirname, 'public')));

const mongoUri = process.env.MONGODB_URI;
const port = process.env.PORT || 3000;
const jwtSecret = process.env.JWT_SECRET || 'change-me';

if (!mongoUri) {
  console.error('MONGODB_URI não configurada. Crie um arquivo .env com a variável.');
  process.exit(1);
}

let db;
let usersCollection;
let devicesCollection;
let segmentsCollection;

async function start() {
  const client = new MongoClient(mongoUri);
  await client.connect();
  db = client.db();
  usersCollection = db.collection('users');
  devicesCollection = db.collection('devices');
  segmentsCollection = db.collection('segments');

  await usersCollection.createIndex({ email: 1 }, { unique: true });
  await devicesCollection.createIndex({ deviceId: 1 }, { unique: true });
  await devicesCollection.createIndex({ deviceKeyFingerprint: 1 }, { unique: true });
  await devicesCollection.createIndex({ userId: 1, name: 1 });
  await segmentsCollection.createIndex({ deviceId: 1, finishedAt: -1 });

  server.listen(port, () => {
    console.log(`Servidor escutando na porta ${port}`);
  });
}

function sanitizeUser(user) {
  return {
    id: user._id.toString(),
    name: user.name,
    email: user.email
  };
}

function signToken(user) {
  return jwt.sign(
    {
      sub: user._id.toString(),
      email: user.email
    },
    jwtSecret,
    { expiresIn: '7d' }
  );
}

function parseAuthorizationHeader(req) {
  const header = req.headers.authorization;
  if (!header) return null;
  const [type, token] = header.split(' ');
  if (type !== 'Bearer' || !token) return null;
  return token;
}

async function authenticateUser(req, res, next) {
  try {
    const token = parseAuthorizationHeader(req);
    if (!token) {
      return res.status(401).json({ error: 'Token ausente' });
    }
    const payload = jwt.verify(token, jwtSecret);
    const user = await usersCollection.findOne({ _id: new ObjectId(payload.sub) });
    if (!user) {
      return res.status(401).json({ error: 'Usuário inválido' });
    }
    req.user = user;
    next();
  } catch (error) {
    return res.status(401).json({ error: 'Token inválido' });
  }
}

function deviceFingerprint(key) {
  return crypto.createHash('sha256').update(key).digest('hex');
}

async function authenticateDevice(req, res, next) {
  try {
    const key = parseAuthorizationHeader(req);
    if (!key) {
      return res.status(401).json({ error: 'Chave do dispositivo ausente' });
    }
    const fingerprint = deviceFingerprint(key);
    const device = await devicesCollection.findOne({ deviceKeyFingerprint: fingerprint });
    if (!device) {
      return res.status(401).json({ error: 'Dispositivo não reconhecido' });
    }
    const valid = await bcrypt.compare(key, device.deviceKeyHash);
    if (!valid) {
      return res.status(401).json({ error: 'Chave inválida' });
    }
    req.device = device;
    next();
  } catch (error) {
    console.error('Erro ao autenticar dispositivo', error);
    return res.status(401).json({ error: 'Falha na autenticação do dispositivo' });
  }
}

io.on('connection', socket => {
  socket.on('authenticate', async token => {
    try {
      const payload = jwt.verify(token, jwtSecret);
      socket.data.userId = payload.sub;
      socket.emit('authenticated');
    } catch (error) {
      socket.emit('auth-error', 'Token inválido');
    }
  });

  socket.on('subscribe-device', async deviceId => {
    if (!socket.data.userId) {
      socket.emit('auth-error', 'Autenticação necessária');
      return;
    }
    const device = await devicesCollection.findOne({
      deviceId,
      userId: socket.data.userId
    });
    if (device) {
      socket.join(`device:${deviceId}`);
      socket.emit('subscribed', deviceId);
    } else {
      socket.emit('auth-error', 'Dispositivo não encontrado');
    }
  });
});

app.post('/api/auth/register', async (req, res) => {
  try {
    const { name, email, password } = req.body;
    if (!name || !email || !password) {
      return res.status(400).json({ error: 'Nome, email e senha são obrigatórios' });
    }
    const normalizedEmail = email.trim().toLowerCase();
    const existing = await usersCollection.findOne({ email: normalizedEmail });
    if (existing) {
      return res.status(409).json({ error: 'Email já registado' });
    }
    const passwordHash = await bcrypt.hash(password, 10);
    const result = await usersCollection.insertOne({
      name: name.trim(),
      email: normalizedEmail,
      passwordHash,
      createdAt: new Date()
    });
    const user = await usersCollection.findOne({ _id: result.insertedId });
    const token = signToken(user);
    res.json({ token, user: sanitizeUser(user) });
  } catch (error) {
    console.error('Erro no registo', error);
    res.status(500).json({ error: 'Falha ao registar utilizador' });
  }
});

app.post('/api/auth/login', async (req, res) => {
  try {
    const { email, password } = req.body;
    if (!email || !password) {
      return res.status(400).json({ error: 'Credenciais inválidas' });
    }
    const normalizedEmail = email.trim().toLowerCase();
    const user = await usersCollection.findOne({ email: normalizedEmail });
    if (!user) {
      return res.status(401).json({ error: 'Credenciais inválidas' });
    }
    const valid = await bcrypt.compare(password, user.passwordHash);
    if (!valid) {
      return res.status(401).json({ error: 'Credenciais inválidas' });
    }
    const token = signToken(user);
    res.json({ token, user: sanitizeUser(user) });
  } catch (error) {
    console.error('Erro no login', error);
    res.status(500).json({ error: 'Falha ao autenticar' });
  }
});

app.get('/api/me', authenticateUser, async (req, res) => {
  res.json({ user: sanitizeUser(req.user) });
});

app.post('/api/devices/register', authenticateUser, async (req, res) => {
  try {
    const name = (req.body.name || '').trim();
    if (!name) {
      return res.status(400).json({ error: 'Nome do dispositivo é obrigatório' });
    }
    const now = new Date();
    const deviceKey = crypto.randomBytes(32).toString('hex');
    const deviceKeyHash = await bcrypt.hash(deviceKey, 10);
    const fingerprint = deviceFingerprint(deviceKey);

    const existing = await devicesCollection.findOne({ userId: req.user._id.toString(), name });
    const deviceId = existing ? existing.deviceId : crypto.randomUUID();

    await devicesCollection.updateOne(
      { userId: req.user._id.toString(), deviceId },
      {
        $set: {
          userId: req.user._id.toString(),
          deviceId,
          name,
          deviceKeyHash,
          deviceKeyFingerprint: fingerprint,
          updatedAt: now
        },
        $setOnInsert: {
          createdAt: now
        }
      },
      { upsert: true }
    );

    res.json({ deviceId, deviceKey, name });
  } catch (error) {
    console.error('Erro ao registar dispositivo', error);
    res.status(500).json({ error: 'Falha ao registar dispositivo' });
  }
});

app.get('/api/devices', authenticateUser, async (req, res) => {
  const devices = await devicesCollection
    .find({ userId: req.user._id.toString() }, {
      projection: {
        _id: 0,
        deviceKeyHash: 0,
        deviceKeyFingerprint: 0
      }
    })
    .sort({ updatedAt: -1 })
    .toArray();
  res.json({ devices });
});

app.post('/api/segments', authenticateDevice, async (req, res) => {
  try {
    const { segment, startedAt, finishedAt, deviceName, deviceId, location } = req.body;
    const normalizedName = typeof deviceName === "string" ? deviceName.trim() : "";
    if (!segment) {
      return res.status(400).json({ error: 'Segmento ausente' });
    }
    if (deviceId && deviceId !== req.device.deviceId) {
      return res.status(403).json({ error: 'Identificador de dispositivo incompatível' });
    }

    const buffer = Buffer.from(segment, 'base64');
    if (!buffer || !buffer.length) {
      return res.status(400).json({ error: 'Segmento inválido' });
    }

    const startedDate = startedAt ? new Date(Number(startedAt)) : new Date();
    const finishedDate = finishedAt ? new Date(Number(finishedAt)) : new Date();
    const durationMs = Math.max(0, finishedDate.getTime() - startedDate.getTime());

    const segmentDoc = {
      userId: req.device.userId,
      deviceId: req.device.deviceId,
      deviceName: normalizedName || req.device.name,
      startedAt: startedDate,
      finishedAt: finishedDate,
      durationMs,
      sizeBytes: buffer.length,
      mimeType: 'video/mp4',
      segment: buffer,
      createdAt: new Date()
    };

    if (location && typeof location === 'object') {
      const lat = Number(location.lat);
      const lng = Number(location.lng);
      if (!Number.isNaN(lat) && !Number.isNaN(lng)) {
        segmentDoc.location = { lat, lng };
      }
    }

    const result = await segmentsCollection.insertOne(segmentDoc);

    const now = new Date();
    const updates = {
      lastSeen: now,
      name: segmentDoc.deviceName || req.device.name
    };
    if (segmentDoc.location) {
      updates.location = segmentDoc.location;
    }

    await devicesCollection.updateOne(
      { deviceId: req.device.deviceId },
      {
        $set: updates,
        $setOnInsert: {
          userId: req.device.userId,
          createdAt: now
        }
      }
    );

    io.to(`device:${req.device.deviceId}`).emit('segment', {
      segmentId: result.insertedId.toString(),
      deviceId: req.device.deviceId,
      finishedAt: finishedDate.toISOString(),
      durationMs,
      sizeBytes: buffer.length
    });

    res.json({ ok: true, segmentId: result.insertedId.toString() });
  } catch (error) {
    console.error('Erro ao guardar segmento', error);
    res.status(500).json({ error: 'Falha ao guardar segmento' });
  }
});

app.get('/api/segments/:deviceId/latest', authenticateUser, async (req, res) => {
  const deviceId = req.params.deviceId;
  const device = await devicesCollection.findOne({
    deviceId,
    userId: req.user._id.toString()
  });
  if (!device) {
    return res.status(404).json({ error: 'Dispositivo não encontrado' });
  }
  const latest = await segmentsCollection
    .find({ deviceId })
    .sort({ finishedAt: -1 })
    .limit(1)
    .next();
  if (!latest) {
    return res.status(404).json({ error: 'Sem segmentos' });
  }
  res.json({
    segment: {
      segmentId: latest._id.toString(),
      deviceId: latest.deviceId,
      deviceName: latest.deviceName,
      startedAt: latest.startedAt,
      finishedAt: latest.finishedAt,
      durationMs: latest.durationMs,
      sizeBytes: latest.sizeBytes,
      mimeType: latest.mimeType,
      location: latest.location || null,
      data: latest.segment.toString('base64')
    }
  });
});

app.get('/api/segments/:deviceId', authenticateUser, async (req, res) => {
  const deviceId = req.params.deviceId;
  const device = await devicesCollection.findOne({
    deviceId,
    userId: req.user._id.toString()
  });
  if (!device) {
    return res.status(404).json({ error: 'Dispositivo não encontrado' });
  }
  const limit = Math.min(parseInt(req.query.limit || '5', 10), 25);
  const segments = await segmentsCollection
    .find({ deviceId })
    .sort({ finishedAt: -1 })
    .limit(limit)
    .toArray();
  res.json({
    segments: segments.map(seg => ({
      segmentId: seg._id.toString(),
      deviceId: seg.deviceId,
      deviceName: seg.deviceName,
      startedAt: seg.startedAt,
      finishedAt: seg.finishedAt,
      durationMs: seg.durationMs,
      sizeBytes: seg.sizeBytes,
      mimeType: seg.mimeType,
      location: seg.location || null,
      data: seg.segment.toString('base64')
    }))
  });
});

start().catch(error => {
  console.error('Erro fatal ao iniciar servidor', error);
  process.exit(1);
});
