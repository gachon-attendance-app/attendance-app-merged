const express = require("express");
const http = require("http");
const cors = require("cors");
require('./firebase/firebaseAdmin');
const attendanceRouter = require('./routes/attendance');
const {initSocketServer} = require('./socket/socketServer');



const app = express();
app.use(cors());
app.use(express.json());
app.use('/api/attendance', attendanceRouter);

app.get("/", (req, res) => {
  res.send("server ok");
});

app.use((req, res) => {
  res.status(404).json({success: false, message: 'Not Found'});
});

const httpServer = http.createServer(app);
initSocketServer(httpServer);

httpServer.listen(3000, () => {
  console.log("Server running on port 3000 (HTTP + WebSocket)");
});
