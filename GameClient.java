import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.*;

public class GameClient extends JFrame {
    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;
    
    private LoginPanel loginPanel;
    private GamePanel gamePanel;
    private MenuPanel menuPanel;
    private RankingPanel rankingPanel;

    private StringBuilder rankBuffer = new StringBuilder();
    private boolean collectingRankings = false;

    public GameClient() {
        initGUI();
        conectarServidor();
    }

    private void initGUI() {
        setTitle("Mini-Jogo Concorrente");
        setSize(800, 600);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        
        loginPanel = new LoginPanel();
        add(loginPanel);
        
        setVisible(true);
    }

    private void conectarServidor() {
        new Thread(() -> {
            try {
                socket = new Socket("127.0.0.1", 12345);
                in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                out = new PrintWriter(socket.getOutputStream(), true);

                String line;
                while ((line = in.readLine()) != null) {
                    processarMensagem(line);
                }
            } catch (IOException e) {
                System.out.println("Erro de conexão: " + e.getMessage());
            }
        }).start();
    }

    private void processarMensagem(String msg) {
        if (msg.equals("RANK_START")) {
            collectingRankings = true;
            rankBuffer.setLength(0); // Limpa o buffer
            return;
        }

        if (msg.equals("RANK_END")) {
            collectingRankings = false;
            SwingUtilities.invokeLater(() -> mudarParaRanking(rankBuffer.toString()));
            return;
        }

        if (collectingRankings) {
            rankBuffer.append(msg).append("\n");
            return;
        }

        // ... resto das tuas lógicas (Login Sucesso, POS, etc) ...
        if (msg.contains("Sucesso: Ola")) {
            SwingUtilities.invokeLater(() -> mudarParaMenu());
        } else if (msg.contains("A partida vai comecar")) {
            SwingUtilities.invokeLater(() -> mudarParaJogo());
        }
    }

    private void mudarParaRanking(String dados) {
        getContentPane().removeAll();
        rankingPanel = new RankingPanel();
        rankingPanel.setTexto(dados);
        add(rankingPanel);
        revalidate();
        repaint();
    }

    private void mudarParaMenu() {
        getContentPane().removeAll();
        menuPanel = new MenuPanel();
        add(menuPanel);
        revalidate();
        repaint();
    }

    private void mudarParaJogo() {
        getContentPane().removeAll();
        gamePanel = new GamePanel();
        add(gamePanel);
        revalidate();
        repaint();
    }
    
    private void mudarParaLogin() {
        getContentPane().removeAll();
        loginPanel = new LoginPanel();
        add(loginPanel);
        revalidate();
        repaint();
    }

    // --- SUB-ECRÃS (Classes Internas) ---

    class LoginPanel extends JPanel {
        JTextField userField = new JTextField(10);
        JPasswordField passField = new JPasswordField(10);
        JButton loginBtn = new JButton("Login");
        JButton registerBtn = new JButton("Registar");
        JButton cancelBtn = new JButton("Apagar Conta");
        JLabel statusLabel = new JLabel("Bem-vindo!");

        public LoginPanel() {
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            JPanel f = new JPanel();
            f.add(new JLabel("User:")); f.add(userField);
            f.add(new JLabel("Pass:")); f.add(passField);
            JPanel b = new JPanel();
            b.add(loginBtn); b.add(registerBtn); b.add(cancelBtn);
            add(f); add(b); add(statusLabel);

            loginBtn.addActionListener(e -> enviar("login"));
            registerBtn.addActionListener(e -> enviar("register"));
            cancelBtn.addActionListener(e -> enviar("cancel"));
        }

        private void enviar(String cmd) {
            out.println(cmd + "," + userField.getText() + "," + new String(passField.getPassword()));
        }

        public void setStatus(String msg) { statusLabel.setText(msg); }
    }

    // --- SUB-ECRÃ DE MENU PRINCIPAL (Atualizado com Feedback) ---
    // --- SUB-ECRÃ DE MENU PRINCIPAL (Corrigido) ---
    class MenuPanel extends JPanel {
        JButton playBtn = new JButton("Procurar Partida");
        JButton rankBtn = new JButton("Ver Rankings");
        JButton logoutBtn = new JButton("Sair/Logout");
        JLabel infoLabel = new JLabel("Escolhe uma opção para continuar.", SwingConstants.CENTER);

        public MenuPanel() {
            setLayout(new GridLayout(4, 1, 10, 10));
            // AQUI ESTAVA O ERRO: createEmptyBorder em vez de Padding
            setBorder(BorderFactory.createEmptyBorder(20, 50, 20, 50));

            infoLabel.setFont(new Font("Arial", Font.BOLD, 14));
            
            add(infoLabel);
            add(playBtn);
            add(rankBtn);
            add(logoutBtn);

            playBtn.addActionListener(e -> {
                out.println("1");
                infoLabel.setText("<html><body style='text-align:center; color:blue;'>" +
                                 "Estás na fila de espera...<br>" +
                                 "A aguardar por mais 2 jogadores.</body></html>");
                playBtn.setEnabled(false);
            });

            rankBtn.addActionListener(e -> {
                out.println("2");
                infoLabel.setText("A carregar rankings...");
            });

            logoutBtn.addActionListener(e -> {
                out.println("3");
                mudarParaLogin();
            });
        }
        
        public void setInfo(String txt) {
            String formattedTxt = "<html><body style='text-align:center;'>" + 
                                  txt.replace("\n", "<br>") + 
                                  "</body></html>";
            infoLabel.setText(formattedTxt);
        }
    }

    class GamePanel extends JPanel {
        int x = 50, y = 50;

        public GamePanel() {
            // Importante: permite que o painel receba foco para detetar teclas
            setFocusable(true);
            requestFocusInWindow();

            addKeyListener(new KeyAdapter() {
                @Override
                public void keyPressed(KeyEvent e) {
                    int code = e.getKeyCode();
                    
                    // Enviamos o comando para o Erlang conforme a tecla
                    if (code == KeyEvent.VK_UP)    out.println("UP");
                    if (code == KeyEvent.VK_DOWN)  out.println("DOWN");
                    if (code == KeyEvent.VK_RIGHT) out.println("RIGHT");
                    if (code == KeyEvent.VK_LEFT)  out.println("LEFT"); // Adicionei esquerda por cortesia!
                }
            });
        }

        public void updatePos(String msg) {
            try {
                String[] p = msg.split(",");
                x = Integer.parseInt(p[1]);
                y = Integer.parseInt(p[2]);
                repaint();
            } catch (Exception e) {}
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            g.setColor(Color.BLACK);
            g.fillRect(0, 0, getWidth(), getHeight());
            g.setColor(Color.GREEN);
            g.fillOval(x, y, 30, 30);
        }
    }

    class RankingPanel extends JPanel {
        JTextArea area = new JTextArea(15, 20);
        JButton backBtn = new JButton("Voltar ao Menu");

        public RankingPanel() {
            setLayout(new BorderLayout());
            area.setEditable(false);
            area.setFont(new Font("Monospaced", Font.BOLD, 14));
            
            add(new JLabel("--- TOP PONTUAÇÕES ---", SwingConstants.CENTER), BorderLayout.NORTH);
            add(new JScrollPane(area), BorderLayout.CENTER);
            add(backBtn, BorderLayout.SOUTH);

            backBtn.addActionListener(e -> mudarParaMenu());
        }

        public void setTexto(String txt) { area.setText(txt); }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new GameClient());
    }
}