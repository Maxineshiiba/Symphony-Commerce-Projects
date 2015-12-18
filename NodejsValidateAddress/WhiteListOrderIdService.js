var mysql = require('mysql');
var WhiteListOrderIdService = (function () {
    function WhiteListOrderIdService(dbConnUrl, logger) {
        this.logger = logger;
        this.dbConnUrl = dbConnUrl;
    }
    ;
    WhiteListOrderIdService.prototype.containsOrderId = function (order_id, callback) {
        var _this = this;
        var sql = 'SELECT order_id FROM whiteListed_order where order_id = ?';
        var connection = mysql.createConnection(this.dbConnUrl);
        connection.query(sql, order_id, function (err, rows) {
            connection.end();
            if (err) {
                _this.logger.error('Unknown server error while executing the SQL query');
                return callback(false);
            }
            callback(rows.length > 0);
        });
    };
    return WhiteListOrderIdService;
})();
module.exports = WhiteListOrderIdService;
//# sourceMappingURL=WhiteListOrderIdService.js.map