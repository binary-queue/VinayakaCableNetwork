package com.saimega.vinayakacablenetwork

import android.content.Intent
import android.os.Bundle
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class LoginActivity : BaseActivity() {

    // ── State ──────────────────────────────────────────────────────────────
    private var isPasswordVisible = false

    // ── View references ────────────────────────────────────────────────────
    private lateinit var etUsername       : EditText
    private lateinit var etPassword       : EditText
    private lateinit var btnTogglePassword: ImageButton
    private lateinit var cbRememberMe     : CheckBox
    private lateinit var tvForgotPassword : TextView
    private lateinit var tvContactSupport : TextView
    private lateinit var btnLogin         : AppCompatButton
    private lateinit var roleAdminOption : View
    private lateinit var roleCollectorOption : View
    private var selectedLoginRole = Roles.ADMIN

    // ── Prefs key ──────────────────────────────────────────────────────────
    private val PREFS_NAME     = "vinayaka_prefs"
    private val KEY_USERNAME   = "username"
    private val KEY_ROLE       = "user_role"
    private val KEY_REMEMBERED = "remembered_username"
    private val KEY_REMEMBER   = "remember_me"

    // ──────────────────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        bindViews()
        restoreRememberedCredentials()
        setListeners()
    }

    // ── Bind all view references ───────────────────────────────────────────
    private fun bindViews() {
        etUsername        = findViewById(R.id.etUsername)
        etPassword        = findViewById(R.id.etPassword)
        btnTogglePassword = findViewById(R.id.btnTogglePassword)
        cbRememberMe      = findViewById(R.id.cbRememberMe)
        tvForgotPassword  = findViewById(R.id.tvForgotPassword)
        tvContactSupport  = findViewById(R.id.tvContactSupport)
        btnLogin          = findViewById(R.id.btnLogin)
        roleAdminOption = findViewById(R.id.roleAdminOption)
        roleCollectorOption = findViewById(R.id.roleCollectorOption)
    }

    // ── Pre-fill saved username if "Remember me" was checked before ────────
    private fun restoreRememberedCredentials() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        selectedLoginRole = if (prefs.getString(KEY_ROLE, Roles.ADMIN).equals(Roles.ADMIN, ignoreCase = true)) {
            Roles.ADMIN
        } else {
            Roles.EMPLOYEE
        }
        updateSelectedLoginRole()
        if (prefs.getBoolean(KEY_REMEMBER, false)) {
            val saved = prefs.getString(KEY_REMEMBERED, "") ?: ""
            if (saved.isNotEmpty()) {
                etUsername.setText(saved)
                cbRememberMe.isChecked = true
            }
        }
    }

    // ── Wire all click / action listeners ─────────────────────────────────
    private fun setListeners() {

        roleAdminOption.setOnClickListener { selectLoginRole(Roles.ADMIN) }
        roleCollectorOption.setOnClickListener { selectLoginRole(Roles.EMPLOYEE) }

        // ── Password visibility toggle ─────────────────────────────────────
        btnTogglePassword.setOnClickListener {
            isPasswordVisible = !isPasswordVisible
            if (isPasswordVisible) {
                etPassword.transformationMethod = HideReturnsTransformationMethod.getInstance()
                btnTogglePassword.setImageResource(R.drawable.ic_login_eye_off)
                btnTogglePassword.setColorFilter(
                    getColor(android.R.color.white),
                    android.graphics.PorterDuff.Mode.SRC_IN
                )
            } else {
                etPassword.transformationMethod = PasswordTransformationMethod.getInstance()
                btnTogglePassword.setImageResource(R.drawable.ic_login_eye)
                btnTogglePassword.clearColorFilter()
            }
            // Keep cursor at end
            etPassword.setSelection(etPassword.text?.length ?: 0)
        }

        // ── Forgot password (placeholder) ─────────────────────────────────
        tvForgotPassword.setOnClickListener {
            Toast.makeText(
                this,
                "Please contact your administrator to reset your password.",
                Toast.LENGTH_LONG
            ).show()
        }

        // ── Contact support (placeholder) ─────────────────────────────────
        tvContactSupport.setOnClickListener {
            Toast.makeText(
                this,
                "Support: vinayakacablenetwork@gmail.com",
                Toast.LENGTH_LONG
            ).show()
        }

        // ── Sign In button ─────────────────────────────────────────────────
        btnLogin.setOnClickListener {
            handleLogin()
        }
    }

    // ── Login validation + navigation logic ───────────────────────────────
    private fun handleLogin() {
        val username = etUsername.text.toString().trim()
        val password = etPassword.text.toString().trim()

        // Validation
        if (username.isEmpty()) {
            etUsername.error = getString(R.string.enter_username)
            etUsername.requestFocus()
            return
        }
        if (password.isEmpty()) {
            etPassword.error = getString(R.string.enter_password)
            etPassword.requestFocus()
            return
        }
        if (password.length < 4) {
            etPassword.error = getString(R.string.password_too_short)
            etPassword.requestFocus()
            return
        }

        btnLogin.isEnabled = false
        lifecycleScope.launch {
            when (val result = AuthRepository().login(username, password)) {
                is LoginResult.Success -> {
                    val accountIsAdmin = result.role.equals(Roles.ADMIN, ignoreCase = true)
                    if (accountIsAdmin != (selectedLoginRole == Roles.ADMIN)) {
                        btnLogin.isEnabled = true
                        val selectedRoleLabel = if (selectedLoginRole == Roles.ADMIN) {
                            getString(R.string.login_role_admin)
                        } else {
                            getString(R.string.login_role_collector)
                        }
                        Toast.makeText(
                            this@LoginActivity,
                            getString(R.string.login_role_mismatch, selectedRoleLabel),
                            Toast.LENGTH_LONG
                        ).show()
                        return@launch
                    }

                    val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    prefs.putString(KEY_USERNAME, result.username)
                    prefs.putString(KEY_ROLE, result.role)
                    if (cbRememberMe.isChecked) {
                        prefs.putString(KEY_REMEMBERED, result.username)
                        prefs.putBoolean(KEY_REMEMBER, true)
                    } else {
                        prefs.remove(KEY_REMEMBERED)
                        prefs.putBoolean(KEY_REMEMBER, false)
                    }
                    prefs.apply()

                    Toast.makeText(this@LoginActivity, getString(R.string.login_successful), Toast.LENGTH_SHORT).show()
                    startActivity(Intent(this@LoginActivity, DashboardActivity::class.java))
                    finish()
                }
                is LoginResult.InvalidCredentials -> {
                    btnLogin.isEnabled = true
                    Toast.makeText(this@LoginActivity, getString(R.string.invalid_credentials), Toast.LENGTH_SHORT).show()
                }
                is LoginResult.AccountDeactivated -> {
                    btnLogin.isEnabled = true
                    Toast.makeText(this@LoginActivity, getString(R.string.account_deactivated), Toast.LENGTH_LONG).show()
                }
                is LoginResult.Failure -> {
                    btnLogin.isEnabled = true
                    Toast.makeText(this@LoginActivity, getString(R.string.error_prefix, result.exception.message), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun selectLoginRole(role: String) {
        selectedLoginRole = role
        updateSelectedLoginRole()
    }

    private fun updateSelectedLoginRole() {
        roleAdminOption.setBackgroundResource(
            if (selectedLoginRole == Roles.ADMIN) R.drawable.bg_login_role_selected
            else R.drawable.bg_login_role_unselected
        )
        roleCollectorOption.setBackgroundResource(
            if (selectedLoginRole == Roles.ADMIN) R.drawable.bg_login_role_unselected
            else R.drawable.bg_login_role_selected
        )
    }
}