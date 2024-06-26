# intention: "TfUnknownResource"
# fix: "Add provider to required providers"
# position: 16: "resource "ably_app" "ably" {"
#
terraform {
  required_providers {
    aws-sso-scim = {
      source  = "burdaforward/aws-sso-scim"
      version = ">= 0.1.0"
    }
    ably = {
      source  = "ably/ably"
      version = "0.6.1"
    }
  }
}

resource "aws-sso-scim_user" "user" {
  display_name = "John Doe"
  family_name  = "Doe"
  given_name   = "John"
  user_name    = "jdoe"
}

resource "ably_app" "ably" {
  name = "app"
}
