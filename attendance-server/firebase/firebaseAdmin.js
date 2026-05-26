const admin = require('firebase-admin');
const serviceAccount = require("./attendanceapp-cbf00-firebase-adminsdk-fbsvc-f149de332d.json");

// databaseURL은 admin SDK 키와 같은 프로젝트여야 함 (다른 프로젝트면 PERMISSION_DENIED).
// 모두 attendanceapp-cbf00로 통일 — Android(FirebaseConfig.BASE_URL)와 동일.
admin.initializeApp({
  credential: admin.credential.cert(serviceAccount),
  databaseURL: "https://attendanceapp-cbf00-default-rtdb.asia-southeast1.firebasedatabase.app"
});

const db = admin.firestore();
const rtdb = admin.database();

module.exports = {admin, db, rtdb};