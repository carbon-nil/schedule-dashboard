package schedule

import java.util.Base64

/** 設定値は環境変数だけから読む (設計書 14 章)。秘密情報をリポジトリに置かない。 */
final case class Config(
    baseUrl: String,
    databasePath: String,
    staticDir: Option[String],
    googleClientId: String,
    googleClientSecret: String,
    ownerGoogleSub: String,
    tokenEncryptionKey: Array[Byte]
)

object Config:
    def fromEnv(): Config =
        def req(name: String): String =
            sys.env.get(name).filter(_.nonEmpty).getOrElse(sys.error(s"environment variable $name is required"))
        Config(
            baseUrl = req("BASE_URL").stripSuffix("/"),
            databasePath = req("DATABASE_PATH"),
            staticDir = sys.env.get("STATIC_DIR").filter(_.nonEmpty),
            googleClientId = req("GOOGLE_CLIENT_ID"),
            googleClientSecret = req("GOOGLE_CLIENT_SECRET"),
            ownerGoogleSub = req("OWNER_GOOGLE_SUB"),
            tokenEncryptionKey = Base64.getDecoder.decode(req("TOKEN_ENCRYPTION_KEY"))
        )
