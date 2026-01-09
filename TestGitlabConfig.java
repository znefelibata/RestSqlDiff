import io.resttestgen.boot.ApiUnderTest;
import io.resttestgen.boot.AuthenticationInfo;

public class TestGitlabConfig {
    public static void main(String[] args) {
        try {
            ApiUnderTest api = ApiUnderTest.loadApiFromFile("gitlab");
            System.out.println("✓ API loaded successfully: " + api.getName());

            AuthenticationInfo authInfo = api.getDefaultAuthenticationInfo();
            if (authInfo != null) {
                System.out.println("✓ Authentication info found");
                System.out.println("  Parameter Name: " + authInfo.getParameterName());
                System.out.println("  Value: " + authInfo.getValue());
                System.out.println("  Location: " + authInfo.getIn());
                System.out.println("  Duration: " + authInfo.getDuration());
            } else {
                System.out.println("✗ No authentication info found");
            }
        } catch (Exception e) {
            System.out.println("✗ Error loading API: " + e.getMessage());
            e.printStackTrace();
        }
    }
}

