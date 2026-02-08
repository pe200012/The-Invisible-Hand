package theinvisiblehand

data class TradeRecord(
    val timestamp: Long,
    val commodityId: String,
    val sourceMarketName: String,
    val destMarketName: String,
    val quantity: Int,
    val profit: Float
)
