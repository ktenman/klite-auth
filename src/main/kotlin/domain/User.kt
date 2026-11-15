package ee.tenman.auth.domain

data class User(val email: String, val name: String = "", val givenName: String = "", val familyName: String = "", val picture: String = "")
