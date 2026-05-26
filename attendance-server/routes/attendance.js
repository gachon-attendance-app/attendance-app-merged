const express = require('express');
const router = express.Router();
const attendanceController = require('../controllers/attendanceController');

router.post('/start', attendanceController.startAttendanceSession);
router.post('/check-in', attendanceController.checkInAttendance);
router.get('/:lectureSessionId/students', attendanceController.getStudents);

module.exports = router;
