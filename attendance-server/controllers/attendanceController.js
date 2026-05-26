const attendanceService = require('../services/attendanceService');

exports.startAttendanceSession = async (req, res) => {
    try {
        const {courseId, professorId, professorUwbAddress} = req.body;
        if(!courseId || !professorId || !professorUwbAddress) {
            return res.status(400).json({
                success: false,
                message: "courseId, professorId, professorUwbAddress are required"
            });
        }

        const result = await attendanceService.startAttendanceSession({
            courseId, professorId, professorUwbAddress
        });
        return res.status(200).json({success: true, data: result});
    } catch(error) {
        console.error('startAttendanceSession error:', error);
        res.status(500).json({ success: false, message: error.message });
    }
}

exports.getStudents = async (req, res) => {
    try {
        const {lectureSessionId} = req.params;
        if(!lectureSessionId) {
            return res.status(400).json({
                success: false,
                message: "lectureSessionId is required"
            });
        }

        const result = await attendanceService.getCheckedInStudents(lectureSessionId);
        return res.status(200).json({success: true, data: result});
    } catch(error) {
        if(error.statusCode) {
            return res.status(error.statusCode).json({
                success: false,
                message: error.message
            });
        }
        console.error('getStudents error:', error);
        res.status(500).json({ success: false, message: error.message });
    }
}

exports.checkInAttendance = async (req, res) => {
    try {
        const {sessionCode, studentId, studentUwbAddress} = req.body;
        if(!sessionCode || !studentId || !studentUwbAddress) {
            return res.status(400).json({
                success: false,
                message: "sessionCode, studentId, studentUwbAddress are required"
            });
        }

        const result = await attendanceService.checkInAttendance({
            sessionCode, studentId, studentUwbAddress
        });
        return res.status(200).json({success: true, data: result});
    } catch(error) {
        // 서비스에서 throw한 의미있는 에러는 statusCode가 붙어있음
        if(error.statusCode) {
            return res.status(error.statusCode).json({
                success: false,
                message: error.message
            });
        }
        console.error('checkInAttendance error:', error);
        res.status(500).json({ success: false, message: error.message });
    }
}
